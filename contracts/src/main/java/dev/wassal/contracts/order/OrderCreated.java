package dev.wassal.contracts.order;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an order is accepted into the system. Written to the outbox in the same
 * transaction as the order row — never published directly (INV-5).
 */
public record OrderCreated(
        UUID orderId,
        UUID merchantId,
        double pickupLat,
        double pickupLon,
        double dropoffLat,
        double dropoffLon,
        Instant createdAt,
        Instant slaDeadline) {}
