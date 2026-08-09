package dev.wassal.dispatch.infra.persistence;

import dev.wassal.contracts.assignment.AssignmentCreated;
import dev.wassal.dispatch.domain.model.AssignmentId;
import dev.wassal.dispatch.domain.model.ClaimResult;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import dev.wassal.dispatch.domain.port.CourierClaimPort;
import dev.wassal.dispatch.infra.outbox.DispatchOutboxWriter;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic claim (ADR-0004). INV-1, INV-2 and INV-3 rest on this class.
 *
 * <p>Three conditional statements in one transaction, with the affected-row count as the decision
 * at every step. There is no read-then-write anywhere on this path, and that is the whole point:
 * {@code if (available) { assign(); }} is the defect this project exists to disprove, and it passes
 * every non-concurrent test ever written against it.
 *
 * <p><strong>Why not a distributed lock.</strong> Every Redis-lock variant puts the claim in one
 * system and the assignment insert in another. A crash in that window leaves a courier locked with
 * no assignment, which then needs a TTL, which then needs a decision about what happens when the
 * TTL fires mid-assignment. The transactional store already provides mutual exclusion for free, so
 * reaching for a lock would trade a solved problem for an interesting one. The benchmark in {@code
 * RedisLockComparisonTest} makes that concrete rather than asserted.
 *
 * <p><strong>The backstop.</strong> Two partial unique indexes on {@code assignments} mean INV-1
 * and INV-2 cannot be violated even if the logic below is wrong tomorrow — the write simply fails.
 * That converts the headline invariants from "enforced by code we tested" into "enforced by a
 * constraint that cannot be bypassed", which is a materially stronger claim.
 */
@Component
public class AtomicClaimExecutor implements CourierClaimPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchOutboxWriter outbox;
    private final AvailabilitySet availability;
    private final InvariantCounters counters;

    public AtomicClaimExecutor(
            NamedParameterJdbcTemplate jdbc,
            DispatchOutboxWriter outbox,
            AvailabilitySet availability,
            InvariantCounters counters) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.availability = availability;
        this.counters = counters;
    }

    /**
     * {@code REQUIRES_NEW} so the claim owns its transaction boundary even when invoked from a
     * caller that already has one. A claim that silently joined an outer transaction would have its
     * rollback semantics decided elsewhere.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult acceptOffer(OfferId offerId, CourierId callerCourierId) {

        // Step 0 — idempotency (INV-3). A replayed accept must return the original assignment,
        // not a second one and not an error. Checked first because it is the cheap path and
        // because a retry over a flaky mobile network is expected traffic, not an anomaly.
        var existing = findAssignmentForOffer(offerId);
        if (existing != null) {
            return new ClaimResult.AlreadyAssigned(
                    AssignmentId.of((UUID) existing.get("id")),
                    OrderId.of((UUID) existing.get("order_id")),
                    CourierId.of((UUID) existing.get("courier_id")));
        }

        // Step 1 — claim the OFFER. Four predicates, every one load-bearing:
        //   id           : which offer
        //   courier_id   : AUTHORIZATION, inside the same statement. Checking ownership in a
        //                  separate query first would be a check-then-act with a race window —
        //                  the exact bug class this file exists to avoid.
        //   status       : not already accepted, declined or expired
        //   expires_at   : the deadline is AUTHORITATIVE (A-07). A late accept loses even if it
        //                  reaches the database before the sweeper, because honouring it would
        //                  open the window where the order has already been re-offered — which
        //                  is precisely the INV-1 hazard.
        // Dropping any one of these is a silent semantic change that no compiler catches.
        int offerClaimed =
                jdbc.update(
                        """
                        UPDATE dispatch.offers
                           SET status = 'ACCEPTED', responded_at = now()
                         WHERE id         = :offerId
                           AND courier_id = :courierId
                           AND status     = 'OFFERED'
                           AND expires_at > now()
                        """,
                        new MapSqlParameterSource()
                                .addValue("offerId", offerId.value())
                                .addValue("courierId", callerCourierId.value()));

        if (offerClaimed == 0) {
            // Zero rows is ambiguous on its own — it could be a wrong courier, a wrong status,
            // or a passed deadline. Distinguishing them is a read, and a read is safe here
            // precisely because we have already lost and are only choosing what to report.
            return diagnoseLostOfferClaim(offerId, callerCourierId);
        }

        var offer = loadOffer(offerId);
        OrderId orderId = OrderId.of((UUID) offer.get("order_id"));

        // Step 2 — claim the COURIER. Conditional on still being AVAILABLE. Zero rows means
        // another accept won them in the window between candidate search and now, which under
        // the stress profile is the common case rather than an error.
        int courierClaimed =
                jdbc.update(
                        """
                        UPDATE dispatch.couriers
                           SET status = 'BUSY', updated_at = now(), version = version + 1
                         WHERE id = :courierId AND status = 'AVAILABLE'
                        """,
                        new MapSqlParameterSource().addValue("courierId", callerCourierId.value()));

        if (courierClaimed == 0) {
            // Roll back the offer claim from step 1 by failing the whole transaction. Returning
            // normally here would COMMIT an ACCEPTED offer with no assignment behind it — the
            // same shape of bug that made 24 orders out of one idempotency key in Sprint 1.
            throw new ClaimLostException(
                    new ClaimResult.CourierUnavailable(offerId, callerCourierId));
        }

        // Step 3 — the assignment itself, plus its event, in the same transaction. The partial
        // unique indexes are the backstop: if steps 1 and 2 were somehow wrong, this insert
        // fails rather than creating the state INV-1 and INV-2 forbid.
        AssignmentId assignmentId = AssignmentId.newId();
        try {
            jdbc.update(
                    """
                    INSERT INTO dispatch.assignments
                           (id, order_id, courier_id, offer_id, status)
                    VALUES (:id, :orderId, :courierId, :offerId, 'ACTIVE')
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", assignmentId.value())
                            .addValue("orderId", orderId.value())
                            .addValue("courierId", callerCourierId.value())
                            .addValue("offerId", offerId.value()));
        } catch (DuplicateKeyException e) {
            // Three constraints can reject here and they mean very different things.
            //
            // uq_active_assignment_per_order (INV-2) is an ORDINARY LOST RACE: two couriers each
            // hold their own offer for the same order, both pass steps 1 and 2 legitimately —
            // different offers, different couriers — and only the order-side index can separate
            // them. Nothing upstream could have prevented it without a second conditional write,
            // and the transaction rolls back cleanly. Counting it as a violation would make the
            // dashboard scream on healthy traffic, which is how a real signal gets ignored.
            //
            // The other two (INV-1, INV-3) SHOULD be unreachable: step 2 already claimed the
            // courier conditionally, and step 0 already checked for an existing assignment. If
            // the database has to catch those, the logic above is wrong and that must be loud.
            if (isOrderRaceRejection(e)) {
                counters.recordFailedClaim();
                throw new ClaimLostException(new ClaimResult.OrderAlreadyAssigned(orderId));
            }
            counters.recordConstraintRejection(e);
            throw new ClaimLostException(new ClaimResult.OrderAlreadyAssigned(orderId));
        }

        outbox.write(
                "Assignment",
                assignmentId.value(),
                "AssignmentCreated",
                new AssignmentCreated(
                        assignmentId.value(),
                        orderId.value(),
                        callerCourierId.value(),
                        Instant.now()));

        // Redis last, and outside the correctness argument. If this line never ran, the index
        // would be stale and the next claim against this courier would simply fail — one wasted
        // attempt, not an incorrect assignment. The index may lie; the claim cannot.
        availability.markUnavailable(callerCourierId);

        counters.recordAssignment();
        return new ClaimResult.Assigned(assignmentId, orderId, callerCourierId);
    }

    private static boolean isOrderRaceRejection(DuplicateKeyException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null && message.contains("uq_active_assignment_per_order");
    }

    private Map<String, Object> findAssignmentForOffer(OfferId offerId) {
        var rows =
                jdbc.queryForList(
                        "SELECT id, order_id, courier_id FROM dispatch.assignments"
                                + " WHERE offer_id = :offerId",
                        new MapSqlParameterSource("offerId", offerId.value()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadOffer(OfferId offerId) {
        return jdbc.queryForMap(
                "SELECT order_id, courier_id FROM dispatch.offers WHERE id = :offerId",
                new MapSqlParameterSource("offerId", offerId.value()));
    }

    private ClaimResult diagnoseLostOfferClaim(OfferId offerId, CourierId caller) {
        var rows =
                jdbc.queryForList(
                        "SELECT courier_id, status::text AS status, expires_at <= now() AS expired"
                                + " FROM dispatch.offers WHERE id = :offerId",
                        new MapSqlParameterSource("offerId", offerId.value()));
        if (rows.isEmpty()) {
            return new ClaimResult.NotOfferRecipient(offerId);
        }
        var row = rows.get(0);
        if (!caller.value().equals(row.get("courier_id"))) {
            return new ClaimResult.NotOfferRecipient(offerId);
        }
        if (Boolean.TRUE.equals(row.get("expired"))) {
            counters.recordExpiredAccept();
            return new ClaimResult.OfferExpired(offerId);
        }

        // The offer was resolved by someone else while we were blocked on its row lock. If that
        // resolution was an ACCEPT, this is a concurrent REPLAY of the same command, not a lost
        // race — INV-3 requires it be answered with the original assignment.
        //
        // Step 0's check ran before the winner committed, so it saw nothing. Re-reading here is
        // safe and correct: our UPDATE waited on the winner's row lock, so by the time we get
        // zero rows the winner's whole transaction — assignment included — has committed.
        var assignment = findAssignmentForOffer(offerId);
        if (assignment != null) {
            return new ClaimResult.AlreadyAssigned(
                    AssignmentId.of((UUID) assignment.get("id")),
                    OrderId.of((UUID) assignment.get("order_id")),
                    CourierId.of((UUID) assignment.get("courier_id")));
        }

        counters.recordFailedClaim();
        return new ClaimResult.CourierUnavailable(offerId, caller);
    }

    /**
     * Carries a {@link ClaimResult} out through a transaction rollback.
     *
     * <p>A lost claim is not exceptional, but rolling back <em>is</em> the only way to undo partial
     * work, and Spring rolls back on throw rather than on return value. So the loss is modelled as
     * a value and merely transported as an exception, unwrapped at the boundary.
     */
    public static class ClaimLostException extends RuntimeException {
        private final transient ClaimResult result;

        public ClaimLostException(ClaimResult result) {
            super(null, null, false, false); // no stack trace: this is control flow, not a fault
            this.result = result;
        }

        public ClaimResult result() {
            return result;
        }
    }
}
