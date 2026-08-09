package dev.wassal.dispatch.infra.persistence;

import dev.wassal.dispatch.domain.model.Candidate;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.domain.port.CandidateFinder;
import dev.wassal.dispatch.domain.service.OfferLifecycle;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a pending order into an offer for the best available candidate (FR-006, FR-007).
 *
 * <p>Replaces the walking skeleton's "first courier in the table". The skeleton's claim shape was
 * already a conditional update with an affected-row check, so this sprint replaced the
 * <em>mechanism</em> without rewriting the call sites — which was the point of writing it that way
 * when it was still trivial.
 */
@Service
public class OfferDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OfferDispatcher.class);

    /** Why no offer was made — the caller decides whether that is terminal. */
    public sealed interface Outcome {
        record Offered(OfferId offerId, Candidate candidate, int sequence) implements Outcome {}

        /** No candidate right now. Not terminal: the order stays pending and is retried. */
        record NoCandidates() implements Outcome {}

        /** Candidate list exhausted — the order must become UNASSIGNABLE, or INV-4 breaks. */
        record Exhausted(int attempts) implements Outcome {}
    }

    private final CandidateFinder candidates;
    private final OfferRepository offers;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final org.springframework.data.redis.core.StringRedisTemplate offerDelivery;

    public OfferDispatcher(
            CandidateFinder candidates,
            OfferRepository offers,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            org.springframework.data.redis.core.StringRedisTemplate offerDelivery) {
        this.candidates = candidates;
        this.offers = offers;
        this.jdbc = jdbc;
        this.offerDelivery = offerDelivery;
    }

    @Transactional
    public Outcome dispatch(OrderId orderId, GeoPoint pickup) {
        int sequence = offers.nextSequence(orderId);

        // Bounded, because "keep trying forever" is not a terminal state and INV-4 requires one.
        if (OfferLifecycle.exhausted(sequence - 1)) {
            return new Outcome.Exhausted(sequence - 1);
        }

        List<String> alreadyOffered = offers.couriersAlreadyOffered(orderId);
        List<Candidate> found =
                candidates.findNearestAvailable(
                        orderId,
                        pickup,
                        OfferLifecycle.SEARCH_RADIUS_METRES,
                        OfferLifecycle.CANDIDATE_LIMIT,
                        alreadyOffered);

        Candidate best = OfferLifecycle.pick(found);
        if (best == null) {
            return new Outcome.NoCandidates();
        }

        OfferId offerId =
                offers.create(orderId, best.courierId(), OfferLifecycle.DEFAULT_TTL, sequence);

        // Deliver the offer to the courier over Pub/Sub, not Kafka. An offer is only useful
        // inside its 15s window, so at-most-once is the right guarantee — and a missed delivery
        // is handled correctly anyway: the sweeper expires it and the next candidate is tried.
        offerDelivery.convertAndSend("offer:courier:" + best.courierId(), offerId.toString());

        log.info(
                "Offered order {} to courier {} at {}m (attempt {})",
                orderId,
                best.courierId(),
                Math.round(best.distanceMetres()),
                sequence);

        return new Outcome.Offered(offerId, best, sequence);
    }

    /** Caches the pickup so a re-offer can run a candidate search without a cross-schema read. */
    @Transactional
    public void rememberPickup(java.util.UUID orderId, double lat, double lon) {
        jdbc.update(
                """
                INSERT INTO dispatch.order_pickups (order_id, pickup_lat, pickup_lon)
                VALUES (:orderId, :lat, :lon)
                ON CONFLICT (order_id) DO NOTHING
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("lat", lat)
                        .addValue("lon", lon));
    }
}
