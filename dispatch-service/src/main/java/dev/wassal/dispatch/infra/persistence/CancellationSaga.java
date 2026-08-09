package dev.wassal.dispatch.infra.persistence;

import dev.wassal.contracts.assignment.AssignmentCancelled;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import dev.wassal.dispatch.infra.outbox.DispatchOutboxWriter;
import dev.wassal.dispatch.infra.redis.RedisGeoCandidateFinder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The compensating saga for cancellation (ADR-0008, FR-009), with persisted progress.
 *
 * <p><strong>The saga is smaller than it looks, and saying so is more useful than drawing four
 * network hops.</strong> Steps 1–3 — cancel the assignment, release the courier, return them to the
 * availability set — all happen in <em>one Postgres transaction</em>, so they cannot diverge. Only
 * returning the order to the pool is genuinely cross-service, and that travels as an event.
 *
 * <p><strong>Why orchestration and not choreography.</strong> "Where is this saga right now?" must
 * be answerable by a query, because crash-mid-saga resumability is a requirement. With choreography
 * the answer would have to be reconstructed by replaying events across services, and a partial
 * failure would leave no record that a saga was ever in flight.
 *
 * <p>On startup, in-flight sagas resume from {@code current_step}. Not from zero: re-running
 * completed steps would rely on idempotency for <em>correctness</em>, when idempotency is meant to
 * be the safety net for a retry rather than the mechanism.
 */
@Service
public class CancellationSaga {

    private static final Logger log = LoggerFactory.getLogger(CancellationSaga.class);
    private static final String SAGA_TYPE = "CANCELLATION";
    private static final int MAX_ATTEMPTS = 5;

    /** Ordered steps. {@code current_step} is an index into this list. */
    private static final List<String> STEPS =
            List.of("CANCEL_ASSIGNMENT", "RELEASE_COURIER", "RESTORE_AVAILABILITY", "EMIT_EVENT");

    public sealed interface Result {
        record Compensated(UUID assignmentId, UUID orderId, UUID courierId) implements Result {}

        /** Already terminal — nothing to compensate, and re-running would be wrong. */
        record AlreadyTerminal(UUID assignmentId) implements Result {}

        record NotFound() implements Result {}

        record NotAssignedCourier() implements Result {}
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchOutboxWriter outbox;
    private final AvailabilitySet availability;
    private final RedisGeoCandidateFinder geoIndex;
    private final MeterRegistry metrics;

    public CancellationSaga(
            NamedParameterJdbcTemplate jdbc,
            DispatchOutboxWriter outbox,
            AvailabilitySet availability,
            RedisGeoCandidateFinder geoIndex,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.availability = availability;
        this.geoIndex = geoIndex;
        this.metrics = metrics;
    }

    @Transactional
    public Result cancel(
            UUID assignmentId, CourierId callerCourierId, UUID triggerEventId, String reason) {

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT order_id, courier_id, status::text AS status"
                                + " FROM dispatch.assignments WHERE id = :id",
                        new MapSqlParameterSource("id", assignmentId));
        if (rows.isEmpty()) {
            return new Result.NotFound();
        }
        Map<String, Object> assignment = rows.get(0);
        UUID orderId = (UUID) assignment.get("order_id");
        UUID courierId = (UUID) assignment.get("courier_id");

        if (!courierId.equals(callerCourierId.value())) {
            return new Result.NotAssignedCourier();
        }
        if (!"ACTIVE".equals(assignment.get("status"))) {
            return new Result.AlreadyTerminal(assignmentId);
        }

        // The unique constraint on (aggregate_id, saga_type, trigger_event_id) is what makes a
        // duplicated trigger a no-op rather than a second compensation. Expressed as a
        // constraint, not as an `if`, so a concurrent duplicate cannot slip between check and
        // insert.
        int started =
                jdbc.update(
                        """
                        INSERT INTO dispatch.sagas (saga_type, aggregate_id, trigger_event_id)
                        VALUES (:type, :aggregateId, :triggerEventId)
                        ON CONFLICT (aggregate_id, saga_type, trigger_event_id) DO NOTHING
                        """,
                        new MapSqlParameterSource()
                                .addValue("type", SAGA_TYPE)
                                .addValue("aggregateId", orderId)
                                .addValue("triggerEventId", triggerEventId));
        if (started == 0) {
            log.debug("Cancellation saga already exists for order {} — duplicate trigger", orderId);
            return new Result.AlreadyTerminal(assignmentId);
        }

        run(assignmentId, orderId, courierId, reason, 0);
        return new Result.Compensated(assignmentId, orderId, courierId);
    }

    /**
     * Executes steps from {@code fromStep} onward. Every step is idempotent and conditional, so
     * resuming is safe — but resumption starts where it stopped, so that safety is a backstop
     * rather than the plan.
     */
    private void run(UUID assignmentId, UUID orderId, UUID courierId, String reason, int fromStep) {
        for (int step = fromStep; step < STEPS.size(); step++) {
            switch (STEPS.get(step)) {
                case "CANCEL_ASSIGNMENT" ->
                        jdbc.update(
                                """
                                UPDATE dispatch.assignments
                                   SET status = 'CANCELLED', cancelled_at = now(),
                                       cancel_reason = :reason
                                 WHERE id = :id AND status = 'ACTIVE'
                                """,
                                new MapSqlParameterSource()
                                        .addValue("id", assignmentId)
                                        .addValue("reason", reason));

                case "RELEASE_COURIER" -> releaseCourierExactlyOnce(assignmentId, courierId);

                case "RESTORE_AVAILABILITY" -> {
                    // Redis, outside the correctness argument. If this never ran the courier
                    // would simply not be offered work until their next availability toggle —
                    // a missed opportunity, never a double-booking.
                    availability.markAvailable(CourierId.of(courierId));
                    restorePosition(courierId);
                }

                case "EMIT_EVENT" ->
                        outbox.write(
                                "Assignment",
                                assignmentId,
                                "AssignmentCancelled",
                                new AssignmentCancelled(
                                        assignmentId, orderId, courierId, reason, Instant.now()));

                default -> throw new IllegalStateException("Unknown saga step");
            }
            advance(orderId, step + 1);
        }
        complete(orderId);
    }

    /**
     * INV-6, and the reason it is non-trivial.
     *
     * <p>The release is conditional on {@code released_at IS NULL}, so replaying the compensation
     * releases the courier once and only once. A doubly-released courier can appear twice in a
     * candidate list and be offered the same order twice — which then threatens INV-1. INV-6 is
     * load-bearing for INV-1, not merely tidy.
     */
    private void releaseCourierExactlyOnce(UUID assignmentId, UUID courierId) {
        int claimed =
                jdbc.update(
                        "UPDATE dispatch.assignments SET released_at = now()"
                                + " WHERE id = :id AND released_at IS NULL",
                        new MapSqlParameterSource("id", assignmentId));
        if (claimed == 0) {
            metrics.counter("wassal_duplicate_release_suppressed_total").increment();
            return;
        }
        jdbc.update(
                "UPDATE dispatch.couriers SET status = 'AVAILABLE', updated_at = now(),"
                        + " version = version + 1 WHERE id = :id AND status = 'BUSY'",
                new MapSqlParameterSource("id", courierId));
    }

    private void restorePosition(UUID courierId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT ST_Y(last_position::geometry) AS lat,"
                                + " ST_X(last_position::geometry) AS lon"
                                + " FROM dispatch.couriers WHERE id = :id"
                                + " AND last_position IS NOT NULL",
                        new MapSqlParameterSource("id", courierId));
        if (!rows.isEmpty()) {
            geoIndex.upsertPosition(
                    CourierId.of(courierId),
                    new dev.wassal.dispatch.domain.model.GeoPoint(
                            ((Number) rows.get(0).get("lat")).doubleValue(),
                            ((Number) rows.get(0).get("lon")).doubleValue()));
        }
    }

    private void advance(UUID orderId, int nextStep) {
        jdbc.update(
                "UPDATE dispatch.sagas SET current_step = :step, updated_at = now()"
                        + " WHERE aggregate_id = :aggregateId AND saga_type = :type"
                        + " AND status = 'STARTED'",
                new MapSqlParameterSource()
                        .addValue("step", nextStep)
                        .addValue("aggregateId", orderId)
                        .addValue("type", SAGA_TYPE));
    }

    private void complete(UUID orderId) {
        jdbc.update(
                "UPDATE dispatch.sagas SET status = 'COMPLETED', updated_at = now()"
                        + " WHERE aggregate_id = :aggregateId AND saga_type = :type"
                        + " AND status = 'STARTED'",
                new MapSqlParameterSource()
                        .addValue("aggregateId", orderId)
                        .addValue("type", SAGA_TYPE));
    }

    /**
     * Resumes sagas left in flight by a crash. Called on startup — this is the half of ADR-0008
     * that choreography could not have provided.
     */
    @Transactional
    public int resumeInFlight() {
        List<Map<String, Object>> inFlight =
                jdbc.queryForList(
                        """
                        SELECT s.id, s.aggregate_id, s.current_step, s.attempts,
                               a.id AS assignment_id, a.courier_id, a.cancel_reason
                          FROM dispatch.sagas s
                          JOIN dispatch.assignments a ON a.order_id = s.aggregate_id
                         WHERE s.status = 'STARTED' AND s.saga_type = :type
                           FOR UPDATE OF s SKIP LOCKED
                        """,
                        new MapSqlParameterSource("type", SAGA_TYPE));

        int resumed = 0;
        for (Map<String, Object> saga : inFlight) {
            int step = (Integer) saga.get("current_step");
            int attempts = (Integer) saga.get("attempts");
            UUID orderId = (UUID) saga.get("aggregate_id");

            if (attempts >= MAX_ATTEMPTS) {
                // Never silently abandoned. For a system with no operator on call, a loud metric
                // is the correct terminal state — retrying forever would be worse.
                jdbc.update(
                        "UPDATE dispatch.sagas SET status = 'FAILED_NEEDS_ATTENTION'"
                                + " WHERE id = :id",
                        new MapSqlParameterSource("id", saga.get("id")));
                metrics.counter("wassal_saga_failed_total").increment();
                log.error("Cancellation saga for order {} exhausted retries", orderId);
                continue;
            }

            jdbc.update(
                    "UPDATE dispatch.sagas SET attempts = attempts + 1 WHERE id = :id",
                    new MapSqlParameterSource("id", saga.get("id")));

            log.info("Resuming cancellation saga for order {} from step {}", orderId, step);
            run(
                    (UUID) saga.get("assignment_id"),
                    orderId,
                    (UUID) saga.get("courier_id"),
                    (String) saga.getOrDefault("cancel_reason", "resumed"),
                    step);
            resumed++;
        }
        return resumed;
    }
}
