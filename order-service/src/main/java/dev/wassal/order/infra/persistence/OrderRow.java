package dev.wassal.order.infra.persistence;

import java.time.Instant;
import java.util.UUID;

/** Row projection. Lives in infra; never crosses into domain or api. */
public record OrderRow(
        UUID id,
        UUID merchantId,
        String status,
        double pickupLat,
        double pickupLon,
        double dropoffLat,
        double dropoffLon,
        UUID assignedCourierId,
        int offerAttempts,
        Instant createdAt,
        Instant terminalAt,
        long version) {}
