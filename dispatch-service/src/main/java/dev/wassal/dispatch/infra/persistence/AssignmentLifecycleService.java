package dev.wassal.dispatch.infra.persistence;

import dev.wassal.contracts.order.OrderDelivered;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import dev.wassal.dispatch.infra.outbox.DispatchOutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pickup and delivery (FR-004). Delivery releases the courier exactly once (INV-6). */
@Service
public class AssignmentLifecycleService {

    public sealed interface Result {
        record Applied(String status) implements Result {}

        record InvalidTransition(String from, String to) implements Result {}

        record NotAssignedCourier() implements Result {}

        record NotFound() implements Result {}
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchOutboxWriter outbox;
    private final AvailabilitySet availability;
    private final MeterRegistry metrics;

    public AssignmentLifecycleService(
            NamedParameterJdbcTemplate jdbc,
            DispatchOutboxWriter outbox,
            AvailabilitySet availability,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.availability = availability;
        this.metrics = metrics;
    }

    @Transactional
    public Result pickUp(UUID assignmentId, CourierId caller) {
        var owned = ownedActive(assignmentId, caller);
        if (!(owned instanceof Result.Applied)) {
            return owned;
        }
        int updated =
                jdbc.update(
                        "UPDATE dispatch.assignments SET picked_up_at = now()"
                                + " WHERE id = :id AND status = 'ACTIVE' AND picked_up_at IS NULL",
                        new MapSqlParameterSource("id", assignmentId));
        // Repeating a pickup is an idempotent no-op, not an error — a retried mobile request
        // must not be punished for arriving twice.
        return new Result.Applied(updated == 1 ? "PICKED_UP" : "PICKED_UP");
    }

    @Transactional
    public Result deliver(UUID assignmentId, CourierId caller) {
        var owned = ownedActive(assignmentId, caller);
        if (!(owned instanceof Result.Applied)) {
            return owned;
        }

        // The machine has no shortcut: delivery requires a pickup first (FR-004).
        Boolean pickedUp =
                jdbc.queryForObject(
                        "SELECT picked_up_at IS NOT NULL FROM dispatch.assignments WHERE id = :id",
                        new MapSqlParameterSource("id", assignmentId),
                        Boolean.class);
        if (!Boolean.TRUE.equals(pickedUp)) {
            return new Result.InvalidTransition("ASSIGNED", "DELIVERED");
        }

        var row =
                jdbc.queryForMap(
                        "SELECT order_id, courier_id FROM dispatch.assignments WHERE id = :id",
                        new MapSqlParameterSource("id", assignmentId));
        UUID orderId = (UUID) row.get("order_id");
        UUID courierId = (UUID) row.get("courier_id");

        jdbc.update(
                "UPDATE dispatch.assignments SET status = 'COMPLETED', completed_at = now()"
                        + " WHERE id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", assignmentId));

        // INV-6 on the delivery path: the same conditional release the saga uses, so a replayed
        // delivery cannot release a courier twice.
        int released =
                jdbc.update(
                        "UPDATE dispatch.assignments SET released_at = now()"
                                + " WHERE id = :id AND released_at IS NULL",
                        new MapSqlParameterSource("id", assignmentId));
        if (released == 1) {
            jdbc.update(
                    "UPDATE dispatch.couriers SET status = 'AVAILABLE', updated_at = now(),"
                            + " version = version + 1 WHERE id = :id AND status = 'BUSY'",
                    new MapSqlParameterSource("id", courierId));
            availability.markAvailable(CourierId.of(courierId));
        } else {
            metrics.counter("wassal_duplicate_release_suppressed_total").increment();
        }

        outbox.write(
                "Order",
                orderId,
                "OrderDelivered",
                new OrderDelivered(orderId, courierId, Instant.now()));

        return new Result.Applied("DELIVERED");
    }

    private Result ownedActive(UUID assignmentId, CourierId caller) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT courier_id, status::text AS status FROM dispatch.assignments"
                                + " WHERE id = :id",
                        new MapSqlParameterSource("id", assignmentId));
        if (rows.isEmpty()) {
            return new Result.NotFound();
        }
        if (!caller.value().equals(rows.get(0).get("courier_id"))) {
            return new Result.NotAssignedCourier();
        }
        if (!"ACTIVE".equals(rows.get(0).get("status"))) {
            return new Result.InvalidTransition((String) rows.get(0).get("status"), "PICKED_UP");
        }
        return new Result.Applied("OK");
    }
}
