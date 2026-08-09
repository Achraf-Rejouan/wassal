package dev.wassal.contracts.order;

import java.time.Instant;
import java.util.UUID;

/** Emitted on delivery. The courier is released exactly once on this path (INV-6). */
public record OrderDelivered(UUID orderId, UUID courierId, Instant deliveredAt) {}
