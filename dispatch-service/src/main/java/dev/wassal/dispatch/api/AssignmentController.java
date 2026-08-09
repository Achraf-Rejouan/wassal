package dev.wassal.dispatch.api;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.infra.persistence.AssignmentLifecycleService;
import dev.wassal.dispatch.infra.persistence.CancellationSaga;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Assignment lifecycle: pickup, deliver, cancel (FR-004, FR-009). */
@RestController
@RequestMapping("/v1/assignments")
public class AssignmentController {

    public record CancelRequest(String reason) {}

    private final AssignmentLifecycleService lifecycle;
    private final CancellationSaga saga;

    public AssignmentController(AssignmentLifecycleService lifecycle, CancellationSaga saga) {
        this.lifecycle = lifecycle;
        this.saga = saga;
    }

    @PostMapping("/{id}/pickup")
    public ResponseEntity<?> pickup(
            @RequestHeader("X-Courier-Id") UUID courierId, @PathVariable UUID id) {
        return map(lifecycle.pickUp(id, CourierId.of(courierId)));
    }

    @PostMapping("/{id}/deliver")
    public ResponseEntity<?> deliver(
            @RequestHeader("X-Courier-Id") UUID courierId, @PathVariable UUID id) {
        return map(lifecycle.deliver(id, CourierId.of(courierId)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(
            @RequestHeader("X-Courier-Id") UUID courierId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID id,
            @RequestBody(required = false) CancelRequest request) {

        // The trigger id is what makes a duplicated cancellation a no-op via the saga's unique
        // constraint. Derived from the client's key when present so a retry maps to the SAME
        // trigger; random otherwise, which at least keeps the constraint well-formed.
        UUID triggerEventId =
                idempotencyKey != null
                        ? UUID.nameUUIDFromBytes(idempotencyKey.getBytes())
                        : UUID.randomUUID();

        var result =
                saga.cancel(
                        id,
                        CourierId.of(courierId),
                        triggerEventId,
                        request != null && request.reason() != null
                                ? request.reason()
                                : "UNSPECIFIED");

        return switch (result) {
            case CancellationSaga.Result.Compensated c -> ResponseEntity.ok(c);
            case CancellationSaga.Result.AlreadyTerminal ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    ErrorResponse.of(
                                            "ORDER_TERMINAL", "Assignment is no longer active"));
            case CancellationSaga.Result.NotAssignedCourier ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(
                                    ErrorResponse.of(
                                            "NOT_ASSIGNED_COURIER", "Caller is not the assignee"));
            case CancellationSaga.Result.NotFound ignored -> ResponseEntity.notFound().build();
        };
    }

    private ResponseEntity<?> map(AssignmentLifecycleService.Result result) {
        return switch (result) {
            case AssignmentLifecycleService.Result.Applied a -> ResponseEntity.ok(a);
            case AssignmentLifecycleService.Result.InvalidTransition t ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    ErrorResponse.of(
                                            "INVALID_STATE_TRANSITION",
                                            "Cannot go from %s to %s".formatted(t.from(), t.to())));
            case AssignmentLifecycleService.Result.NotAssignedCourier ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(
                                    ErrorResponse.of(
                                            "NOT_ASSIGNED_COURIER", "Caller is not the assignee"));
            case AssignmentLifecycleService.Result.NotFound ignored ->
                    ResponseEntity.notFound().build();
        };
    }
}
