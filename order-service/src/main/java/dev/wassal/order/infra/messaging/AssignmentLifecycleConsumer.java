package dev.wassal.order.infra.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wassal.contracts.assignment.AssignmentCancelled;
import dev.wassal.contracts.order.OrderUnassignable;
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
 * Closes the order-side half of the compensating saga and of candidate exhaustion.
 *
 * <p>This is where INV-4 is actually satisfied: an order that has been cancelled or found
 * unassignable reaches a <em>terminal</em> state here, and {@code chk_terminal_consistency} makes
 * the stuck-order gauge able to see anything that does not.
 */
@Component
public class AssignmentLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(AssignmentLifecycleConsumer.class);
    private static final String CONSUMER_GROUP = "order-assignment-lifecycle";

    private final ObjectMapper objectMapper;
    private final OrderRepository orders;
    private final NamedParameterJdbcTemplate jdbc;

    public AssignmentLifecycleConsumer(
            ObjectMapper objectMapper, OrderRepository orders, NamedParameterJdbcTemplate jdbc) {
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @KafkaListener(
            topics = "${wassal.topics.assignment-lifecycle}",
            groupId = CONSUMER_GROUP + "-2")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String eventType = header(record, "event-type");
        if (eventType == null) {
            return;
        }
        UUID messageId = messageIdOf(record);
        if (!claim(messageId)) {
            return;
        }

        switch (eventType) {
            case "AssignmentCancelled" -> {
                var event = objectMapper.readValue(record.value(), AssignmentCancelled.class);
                // Back to the pool. The order is NOT terminal — it is available to be offered
                // again, which is what makes ASSIGNED -> PENDING the compensating edge.
                boolean applied =
                        orders.applyTransition(
                                OrderId.of(event.orderId()),
                                OrderStatus.ASSIGNED,
                                OrderStatus.PENDING);
                if (applied) {
                    jdbc.update(
                            "UPDATE orders.orders SET assigned_courier_id = NULL"
                                    + " WHERE id = :orderId",
                            new MapSqlParameterSource("orderId", event.orderId()));
                    log.info("Order {} returned to the pool after cancellation", event.orderId());
                }
            }
            case "OrderUnassignable" -> {
                var event = objectMapper.readValue(record.value(), OrderUnassignable.class);
                // Terminal. Without this an exhausted order would sit PENDING forever, which is
                // precisely the INV-4 violation the whole sprint exists to prevent.
                for (OrderStatus from :
                        new OrderStatus[] {OrderStatus.PENDING, OrderStatus.OFFERING}) {
                    if (orders.applyTransition(
                            OrderId.of(event.orderId()), from, OrderStatus.UNASSIGNABLE)) {
                        log.warn(
                                "Order {} UNASSIGNABLE after {} attempts",
                                event.orderId(),
                                event.attempts());
                        break;
                    }
                }
            }
            default -> log.debug("Ignoring event type {}", eventType);
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
                                .addValue("consumerGroup", CONSUMER_GROUP + "-2"))
                == 1;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static UUID messageIdOf(ConsumerRecord<String, String> record) {
        String id = header(record, "message-id");
        if (id != null) {
            return UUID.fromString(id);
        }
        return UUID.nameUUIDFromBytes(
                (record.topic() + ":" + record.partition() + ":" + record.offset())
                        .getBytes(StandardCharsets.UTF_8));
    }
}
