package dev.wassal.contracts.assignment;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a deadline passes with no response. Drives the re-offer (FR-007). */
public record OfferExpired(
        UUID offerId, UUID orderId, UUID courierId, Instant deadline, Instant expiredAt) {}
