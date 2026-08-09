package dev.wassal.gateway.subscription;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler for {@code GET /v1/orders/{id}/stream} (FR-016).
 *
 * <p><strong>Reconnect semantics are a design choice, not a simplification.</strong> On
 * (re)subscribe the client receives the <em>current</em> position immediately; missed intermediate
 * positions are never replayed. Positions are current-state, not an event stream — replaying them
 * would animate a courier retracing a path they already travelled, which is visibly wrong and more
 * expensive than doing nothing.
 */
@Component
public class OrderStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderStreamHandler.class);

    private final SubscriptionRegistry registry;
    private final StringRedisTemplate redis;

    public OrderStreamHandler(SubscriptionRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String orderId = orderIdOf(session);
        if (orderId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("order id missing"));
            return;
        }

        // dispatch-service writes this mapping when a claim succeeds. The gateway holds no
        // domain state, so it looks the assignment up rather than tracking it.
        String courierId = redis.opsForValue().get("assign:order:" + orderId);
        if (courierId == null) {
            // Not an error: the order may simply not be assigned yet. The socket stays open and
            // starts carrying positions once dispatch publishes the mapping.
            send(
                    session,
                    "{\"type\":\"status\",\"orderId\":\"%s\",\"status\":\"UNASSIGNED\"}"
                            .formatted(orderId));
            return;
        }

        if (!registry.register(session, courierId)) {
            // Global cap reached. Refusing loudly beats accepting a socket that will be starved.
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }

        sendCurrentPosition(session, courierId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
    }

    /** Current state on subscribe — never a replay of what was missed. */
    private void sendCurrentPosition(WebSocketSession session, String courierId)
            throws IOException {
        Map<Object, Object> hot = redis.opsForHash().entries("hot:pos:" + courierId);
        if (hot.isEmpty()) {
            send(
                    session,
                    "{\"type\":\"status\",\"courierId\":\"%s\",\"status\":\"NO_POSITION\"}"
                            .formatted(courierId));
            return;
        }
        long recordedAt = Long.parseLong((String) hot.get("recordedAt"));
        boolean stale = System.currentTimeMillis() - recordedAt > 60_000;
        // Built with a StringBuilder rather than a concatenated format string: `.formatted()`
        // binds to the LAST string literal, not to the whole concatenation, so a `+` here
        // silently formatted only the tail and fed courierId to a %d (see docs/bug-log.md).
        String frame =
                new StringBuilder(160)
                        .append("{\"type\":\"position\",\"courierId\":\"")
                        .append(courierId)
                        .append("\",\"lat\":")
                        .append(hot.get("lat"))
                        .append(",\"lon\":")
                        .append(hot.get("lon"))
                        .append(",\"recordedAt\":")
                        .append(recordedAt)
                        .append(",\"stale\":")
                        .append(stale)
                        .append("}")
                        .toString();
        send(session, frame);
    }

    private void send(WebSocketSession session, String payload) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }

    private static String orderIdOf(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("orders".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }
}
