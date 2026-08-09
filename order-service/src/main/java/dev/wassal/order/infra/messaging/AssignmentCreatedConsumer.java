package dev.wassal.order.infra.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wassal.contracts.assignment.AssignmentCreated;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.model.OrderStatus;
import dev.wassal.order.domain.port.OrderRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the walking-skeleton loop: consumes {@code AssignmentCreated} from dispatch and moves the
 * order to {@code ASSIGNED}.
 *
 * <p>This is the point where the eventual-consistency window documented in the architecture becomes
 * real — between dispatch committing the assignment and this consumer running, an assignment exists
 * for an order still marked {@code PENDING}. That is correct, bounded by publisher poll plus
 * consume latency, and the reconciliation job must classify it as in-flight rather than variance
 * (review finding F-9).
 */
@Component
public class AssignmentCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(AssignmentCreatedConsumer.class);
    private static final String CONSUMER_GROUP = "order-assignment-lifecycle";

    private final ObjectMapper objectMapper;
    private final OrderRepository orders;
    private final NamedParameterJdbcTemplate jdbc;

    public AssignmentCreatedConsumer(
            ObjectMapper objectMapper, OrderRepository orders, NamedParameterJdbcTemplate jdbc) {
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "${wassal.topics.assignment-lifecycle}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onAssignmentCreated(ConsumerRecord<String, String> record) throws Exception {
        // The topic now carries several event types (expiry, cancellation, unassignable), so a
        // consumer that assumed a single shape would deserialise garbage into the wrong record.
        var typeHeader = record.headers().lastHeader("event-type");
        String eventType =
                typeHeader == null ? null : new String(typeHeader.value(), StandardCharsets.UTF_8);
        if (!"AssignmentCreated".equals(eventType)) {
            return;
        }

        UUID messageId = messageIdOf(record);
        if (!claim(messageId)) {
            return;
        }

        AssignmentCreated event = objectMapper.readValue(record.value(), AssignmentCreated.class);
        OrderId orderId = OrderId.of(event.orderId());

        // Conditional transition: PENDING -> ASSIGNED only. If the order has moved on (cancelled,
        // already assigned), this affects zero rows and we take the no-op branch rather than
        // forcing a state the machine forbids.
        boolean applied =
                orders.applyTransition(orderId, OrderStatus.PENDING, OrderStatus.ASSIGNED);

        if (applied) {
            jdbc.update(
                    "UPDATE orders.orders SET assigned_courier_id = :courierId WHERE id = :orderId",
                    new MapSqlParameterSource()
                            .addValue("courierId", event.courierId())
                            .addValue("orderId", event.orderId()));
            log.info("Order {} -> ASSIGNED (courier {})", event.orderId(), event.courierId());
        } else {
            log.info("Order {} was not PENDING; assignment event ignored", event.orderId());
        }
    }

    private boolean claim(UUID messageId) {
        return jdbc.update(
                        """
                        INSERT INTO orders.processed_messages (message_id, consumer_group)
                        VALUES (:messageId, :consumerGroup)
                        ON CONFLICT (message_id, consumer_group) DO NOTHING
                        """,
                        new MapSqlParameterSource()
                                .addValue("messageId", messageId)
                                .addValue("consumerGroup", CONSUMER_GROUP))
                == 1;
    }

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
