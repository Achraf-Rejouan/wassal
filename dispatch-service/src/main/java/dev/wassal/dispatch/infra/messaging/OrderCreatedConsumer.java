package dev.wassal.dispatch.infra.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wassal.contracts.order.OrderCreated;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.infra.persistence.OfferDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code OrderCreated} and performs the walking skeleton's trivial assignment.
 *
 * <p>The whole method is one transaction covering the dedup claim, the courier claim, the
 * assignment insert and the outbox write. That is what makes the effect effectively-once under
 * at-least-once delivery: a redelivery after a crash finds either all of it committed or none of
 * it, never half.
 *
 * <p>Offsets are committed by the container only after this returns normally, so a crash
 * mid-processing replays the message rather than losing it (INV-5).
 */
@Component
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);
    private static final String CONSUMER_GROUP = "dispatch-order-lifecycle";

    private final ObjectMapper objectMapper;
    private final InboxDedup dedup;
    private final OfferDispatcher dispatcher;

    public OrderCreatedConsumer(
            ObjectMapper objectMapper, InboxDedup dedup, OfferDispatcher dispatcher) {
        this.objectMapper = objectMapper;
        this.dedup = dedup;
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = "${wassal.topics.order-lifecycle}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record) throws Exception {
        UUID messageId = messageIdOf(record);

        if (!dedup.claim(messageId, CONSUMER_GROUP)) {
            log.debug("Duplicate message {} suppressed", messageId);
            return;
        }

        OrderCreated event = objectMapper.readValue(record.value(), OrderCreated.class);

        // dispatch cannot read orders.orders (module boundary, and the per-service Postgres
        // role forbids it), so the pickup rides along on the event and is cached for re-offers.
        dispatcher.rememberPickup(event.orderId(), event.pickupLat(), event.pickupLon());

        var outcome =
                dispatcher.dispatch(
                        OrderId.of(event.orderId()),
                        new GeoPoint(event.pickupLat(), event.pickupLon()));

        switch (outcome) {
            case OfferDispatcher.Outcome.Offered offered ->
                    log.info(
                            "Order {} offered as {} (attempt {})",
                            event.orderId(),
                            offered.offerId(),
                            offered.sequence());
            case OfferDispatcher.Outcome.NoCandidates ignored ->
                    // Not terminal. The order stays pending; Sprint 3's sweeper retries it.
                    log.info("No available courier for order {}", event.orderId());
            case OfferDispatcher.Outcome.Exhausted exhausted ->
                    log.warn(
                            "Order {} exhausted {} candidates — becomes UNASSIGNABLE in Sprint 3",
                            event.orderId(),
                            exhausted.attempts());
        }
    }

    /**
     * The producing outbox row's id, carried as a header. Falls back to a topic-partition-offset
     * identity if absent — a message without a message-id header cannot be deduped by content, and
     * silently processing it twice would be worse than deriving a stable synthetic key.
     */
    private static UUID messageIdOf(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("message-id");
        if (header != null) {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        }
        return UUID.nameUUIDFromBytes(
                (record.topic() + ":" + record.partition() + ":" + record.offset())
                        .getBytes(StandardCharsets.UTF_8));
    }
}
