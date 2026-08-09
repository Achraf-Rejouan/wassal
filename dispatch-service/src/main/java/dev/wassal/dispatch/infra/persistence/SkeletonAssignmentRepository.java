package dev.wassal.dispatch.infra.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Walking-skeleton assignment writes.
 *
 * <p>Deliberately trivial: it takes the first AVAILABLE courier with no geospatial search, no
 * offer, and no deadline. The atomic claim (ADR-0004) arrives in Sprint 2 and replaces {@link
 * #tryAssign} entirely.
 *
 * <p>Even so the claim shape is already correct — a conditional {@code UPDATE} whose affected-row
 * count decides the outcome, not a read followed by a write. Writing the skeleton with a
 * check-then-act would mean Sprint 2 rewrites the call sites rather than the mechanism, and the
 * partial unique indexes are already in place to catch it if this is wrong.
 */
@Repository
public class SkeletonAssignmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SkeletonAssignmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the assignment id, or empty if no courier could be claimed
     */
    public Optional<Assignment> tryAssign(UUID orderId) {
        // SKIP LOCKED so concurrent dispatches take different couriers rather than serialising
        // on the same row — the same pattern the Sprint 2 claim and the Sprint 3 sweeper use.
        var candidate =
                jdbc.queryForList(
                        """
                        SELECT id FROM dispatch.couriers
                         WHERE status = 'AVAILABLE'
                         ORDER BY id
                         LIMIT 1
                           FOR UPDATE SKIP LOCKED
                        """,
                        new MapSqlParameterSource());
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID courierId = (UUID) candidate.get(0).get("id");

        // Conditional: only claims a courier still AVAILABLE. Zero rows means someone else won.
        int claimed =
                jdbc.update(
                        """
                        UPDATE dispatch.couriers SET status = 'BUSY', updated_at = now(),
                               version = version + 1
                         WHERE id = :courierId AND status = 'AVAILABLE'
                        """,
                        new MapSqlParameterSource("courierId", courierId));
        if (claimed != 1) {
            return Optional.empty();
        }

        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO dispatch.assignments (id, order_id, courier_id, status)
                VALUES (:id, :orderId, :courierId, 'ACTIVE')
                """,
                new MapSqlParameterSource()
                        .addValue("id", assignmentId)
                        .addValue("orderId", orderId)
                        .addValue("courierId", courierId));

        return Optional.of(new Assignment(assignmentId, orderId, courierId));
    }

    public record Assignment(UUID id, UUID orderId, UUID courierId) {}
}
