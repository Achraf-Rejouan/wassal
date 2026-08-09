package dev.wassal.order.api;

import java.time.Instant;
import java.util.UUID;

/** Wire shape for a created or queried order. */
public record OrderResponse(
        UUID id, String status, Instant createdAt, Instant slaDeadline, int offerAttempts) {}
