package dev.wassal.dispatch.domain.service;

import dev.wassal.dispatch.domain.model.Candidate;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import java.time.Duration;

/** Offer lifecycle policy (FR-007). Pure values — no framework, no I/O. */
public final class OfferLifecycle {

    /** Default response window. The deadline is persisted, never held in a timer (ADR-0005). */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(15);

    /** Nearest-N within radius. Beyond this the order is UNASSIGNABLE rather than hanging. */
    public static final int CANDIDATE_LIMIT = 5;

    public static final double SEARCH_RADIUS_METRES = 3_000;

    /**
     * Candidate attempts before an order is declared UNASSIGNABLE. Bounded so INV-4 holds: an order
     * must reach a terminal state, and "keep trying forever" is not one.
     */
    public static final int MAX_OFFER_ATTEMPTS = 10;

    private OfferLifecycle() {}

    public record OfferRequest(
            OrderId orderId,
            GeoPoint pickup,
            int nextSequence,
            java.util.List<String> alreadyOffered) {}

    public record Offered(OfferId offerId, CourierId courierId, int sequence) {}

    public static boolean exhausted(int attempts) {
        return attempts >= MAX_OFFER_ATTEMPTS;
    }

    public static Candidate pick(java.util.List<Candidate> candidates) {
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
