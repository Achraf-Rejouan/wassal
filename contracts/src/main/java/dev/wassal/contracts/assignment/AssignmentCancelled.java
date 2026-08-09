package dev.wassal.contracts.assignment;

import java.time.Instant;
import java.util.UUID;

/** Emitted when the compensating saga completes (FR-009). Returns the order to the pool. */
public record AssignmentCancelled(
        UUID assignmentId, UUID orderId, UUID courierId, String reason, Instant cancelledAt) {}
