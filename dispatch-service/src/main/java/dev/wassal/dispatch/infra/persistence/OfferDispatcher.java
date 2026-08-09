package dev.wassal.dispatch.infra.persistence;

import dev.wassal.dispatch.domain.model.Candidate;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.domain.port.CandidateFinder;
import dev.wassal.dispatch.domain.service.OfferLifecycle;
import java.util.List;
import java.util.Optional;
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

    public OfferDispatcher(CandidateFinder candidates, OfferRepository offers) {
        this.candidates = candidates;
        this.offers = offers;
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

        log.info(
                "Offered order {} to courier {} at {}m (attempt {})",
                orderId,
                best.courierId(),
                Math.round(best.distanceMetres()),
                sequence);

        return new Outcome.Offered(offerId, best, sequence);
    }

    public Optional<OfferId> currentOffer(OrderId orderId) {
        return Optional.empty();
    }
}
