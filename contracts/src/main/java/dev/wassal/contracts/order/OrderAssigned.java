package dev.wassal.contracts.order;

import java.time.Instant;
import java.util.UUID;

/** Emitted when an order transitions to ASSIGNED after a successful claim. */
public record OrderAssigned(UUID orderId, UUID courierId, UUID assignmentId, Instant assignedAt) {}
