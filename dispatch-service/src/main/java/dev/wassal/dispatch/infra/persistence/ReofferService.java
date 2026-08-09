package dev.wassal.dispatch.infra.persistence;

import dev.wassal.contracts.order.OrderUnassignable;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.infra.outbox.DispatchOutboxWriter;
import dev.wassal.dispatch.infra.sweeper.OfferExpirySweeper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Re-offers an order after a decline or an expiry, or declares it UNASSIGNABLE (FR-007).
 *
 * <p>UNASSIGNABLE is not a failure mode — it is what stops INV-4 from being violated. An order that
 * keeps trying forever never reaches a terminal state, so exhaustion has to be a decision rather
 * than an absence of one.
 */
@Service
public class ReofferService implements OfferExpirySweeper.ReofferPort {

    private static final Logger log = LoggerFactory.getLogger(ReofferService.class);

    private final OfferDispatcher dispatcher;
    private final DispatchOutboxWriter outbox;
    private final NamedParameterJdbcTemplate jdbc;

    public ReofferService(
            OfferDispatcher dispatcher,
            DispatchOutboxWriter outbox,
            NamedParameterJdbcTemplate jdbc) {
        this.dispatcher = dispatcher;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /**
     * Joins the caller's transaction deliberately — no {@code @Transactional} of its own. The
     * sweeper expires an offer and re-offers in one unit, so an order cannot be left with its
     * previous offer expired and no successor.
     */
    @Override
    public void reofferAfterFailedAttempt(UUID orderId) {
        GeoPoint pickup = pickupOf(orderId);
        if (pickup == null) {
            log.warn("No pickup recorded for order {} — cannot re-offer", orderId);
            return;
        }

        var outcome = dispatcher.dispatch(OrderId.of(orderId), pickup);
        switch (outcome) {
            case OfferDispatcher.Outcome.Offered offered ->
                    log.info(
                            "Re-offered order {} as {} (attempt {})",
                            orderId,
                            offered.offerId(),
                            offered.sequence());
            case OfferDispatcher.Outcome.NoCandidates ignored ->
                    // Not terminal: nobody is free right now, but someone may be shortly. The
                    // order stays pending and the SLA gauge is what catches it if it never is.
                    log.debug("No candidate for order {} on re-offer", orderId);
            case OfferDispatcher.Outcome.Exhausted exhausted ->
                    markUnassignable(orderId, exhausted);
        }
    }

    private void markUnassignable(UUID orderId, OfferDispatcher.Outcome.Exhausted exhausted) {
        log.warn("Order {} exhausted {} candidates — UNASSIGNABLE", orderId, exhausted.attempts());
        outbox.write(
                "Order",
                orderId,
                "OrderUnassignable",
                new OrderUnassignable(orderId, exhausted.attempts(), Instant.now()));
    }

    /**
     * Pickup is read from the first offer's order. dispatch-service does not own the orders table
     * and must not read it (module boundaries), so the coordinates ride along on the OrderCreated
     * event and are cached here when the first offer is made.
     */
    private GeoPoint pickupOf(UUID orderId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT pickup_lat, pickup_lon FROM dispatch.order_pickups"
                                + " WHERE order_id = :orderId",
                        new MapSqlParameterSource("orderId", orderId));
        if (rows.isEmpty()) {
            return null;
        }
        return new GeoPoint(
                ((Number) rows.get(0).get("pickup_lat")).doubleValue(),
                ((Number) rows.get(0).get("pickup_lon")).doubleValue());
    }
}
