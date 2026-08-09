package dev.wassal.contracts.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when the candidate list is exhausted. A terminal state, which is what stops INV-4 from
 * being violated by an order that simply keeps trying forever.
 */
public record OrderUnassignable(UUID orderId, int attempts, Instant at) {}
