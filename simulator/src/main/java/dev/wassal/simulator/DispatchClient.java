package dev.wassal.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.wassal.simulator.groundtruth.GroundTruthSink;
import dev.wassal.simulator.profile.SimulationProfile;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The simulator's only route into the system: HTTP, exactly as a real client would use.
 *
 * <p>Offers arrive over Redis Pub/Sub because that is how dispatch delivers them; everything else
 * is REST. No database connection exists in this module, and an ArchUnit rule keeps it that way —
 * the independence is what makes FR-020's ground truth worth anything.
 */
@Component
public class DispatchClient implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchClient.class);

    private record PendingOffer(UUID offerId, UUID courierId, Instant received) {}

    private final Deque<PendingOffer> pending = new ArrayDeque<>();
    private final RestClient gateway;
    private final RestClient dispatch;
    private final RestClient tracking;
    private final UUID merchantId = UUID.randomUUID();

    public DispatchClient(
            RestClient.Builder builder,
            RedisMessageListenerContainer container,
            @Value("${wassal.sim.gateway-url}") String gatewayUrl,
            @Value("${wassal.sim.dispatch-url}") String dispatchUrl,
            @Value("${wassal.sim.tracking-url}") String trackingUrl) {
        this.gateway = builder.clone().baseUrl(gatewayUrl).build();
        this.dispatch = builder.clone().baseUrl(dispatchUrl).build();
        this.tracking = builder.clone().baseUrl(trackingUrl).build();
        container.addMessageListener(this, new PatternTopic("offer:courier:*"));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String courierId = channel.substring(channel.lastIndexOf(':') + 1);
        String offerId = new String(message.getBody());
        synchronized (pending) {
            pending.add(
                    new PendingOffer(
                            UUID.fromString(offerId), UUID.fromString(courierId), Instant.now()));
        }
    }

    public void goAvailable(UUID courierId, double lat, double lon) {
        try {
            dispatch.post()
                    .uri("/v1/couriers/{id}/availability", courierId)
                    .header("X-Courier-Id", courierId.toString())
                    .header("Content-Type", "application/json")
                    .body(Map.of("available", true, "lat", lat, "lon", lon))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.debug("goAvailable failed for {}: {}", courierId, e.getMessage());
        }
    }

    public void reportPosition(UUID courierId, double lat, double lon, double speedKmh) {
        try {
            tracking.post()
                    .uri("/v1/couriers/{id}/location", courierId)
                    .header("X-Courier-Id", courierId.toString())
                    .header("Content-Type", "application/json")
                    .body(
                            Map.of(
                                    "lat", lat,
                                    "lon", lon,
                                    "recordedAt", Instant.now().toString(),
                                    "speedKmh", speedKmh))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // Dropped and counted, never queued: a backlog of stale positions is worse than
            // none, and the hot path is a cache rather than a ledger.
            log.debug("position report failed: {}", e.getMessage());
        }
    }

    public UUID createOrder(double lat, double lon) {
        try {
            JsonNode response =
                    gateway.post()
                            .uri("/v1/orders")
                            .header("X-Merchant-Id", merchantId.toString())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .header("Content-Type", "application/json")
                            .body(
                                    Map.of(
                                            "pickup", Map.of("lat", lat, "lon", lon),
                                            "dropoff",
                                                    Map.of("lat", lat + 0.01, "lon", lon + 0.01)))
                            .retrieve()
                            .body(JsonNode.class);
            return response == null ? null : UUID.fromString(response.get("id").asText());
        } catch (RuntimeException e) {
            log.debug("createOrder failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Responds to outstanding offers with the profile's calibrated probabilities.
     *
     * <p>The ignore branch does nothing at all — deliberately. Those offers reach their deadline
     * naturally and are expired by the sweeper, which means FR-011 is exercised continuously by
     * ordinary traffic rather than only in dedicated tests.
     */
    public void respondToOffers(Random random, SimulationProfile profile, GroundTruthSink truth) {
        while (true) {
            PendingOffer offer;
            synchronized (pending) {
                offer = pending.poll();
            }
            if (offer == null) {
                return;
            }

            double roll = random.nextDouble();
            if (roll < profile.acceptRate()) {
                accept(offer, random, profile, truth);
            } else if (roll < profile.acceptRate() + profile.declineRate()) {
                decline(offer);
                truth.record("offer_declined", Map.of("offerId", offer.offerId().toString()));
            } else {
                truth.record("offer_ignored", Map.of("offerId", offer.offerId().toString()));
            }
        }
    }

    private void accept(
            PendingOffer offer, Random random, SimulationProfile profile, GroundTruthSink truth) {
        try {
            JsonNode assignment =
                    dispatch.post()
                            .uri("/v1/offers/{id}/accept", offer.offerId())
                            .header("X-Courier-Id", offer.courierId().toString())
                            .retrieve()
                            .body(JsonNode.class);
            if (assignment == null || !assignment.has("assignmentId")) {
                return;
            }
            String assignmentId = assignment.get("assignmentId").asText();
            truth.record(
                    "offer_accepted",
                    Map.of(
                            "offerId", offer.offerId().toString(),
                            "courierId", offer.courierId().toString(),
                            "assignmentId", assignmentId));

            if (random.nextDouble() < profile.postAcceptCancelRate()) {
                dispatch.post()
                        .uri("/v1/assignments/{id}/cancel", assignmentId)
                        .header("X-Courier-Id", offer.courierId().toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Content-Type", "application/json")
                        .body(Map.of("reason", "SIMULATED_CANCELLATION"))
                        .retrieve()
                        .toBodilessEntity();
                truth.record("assignment_cancelled", Map.of("assignmentId", assignmentId));
            }
        } catch (RuntimeException e) {
            // 409 and 410 are ordinary here — losing a race or missing a deadline is exactly
            // what the profile is designed to produce.
            log.debug("accept lost for offer {}: {}", offer.offerId(), e.getMessage());
        }
    }

    private void decline(PendingOffer offer) {
        try {
            dispatch.post()
                    .uri("/v1/offers/{id}/decline", offer.offerId())
                    .header("X-Courier-Id", offer.courierId().toString())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.debug("decline failed: {}", e.getMessage());
        }
    }
}
