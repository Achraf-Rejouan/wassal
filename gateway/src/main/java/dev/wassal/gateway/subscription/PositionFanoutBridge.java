package dev.wassal.gateway.subscription;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The cross-instance bridge (challenge 3.5, ADR-0007).
 *
 * <p>This is the whole point of the gateway being a separate, horizontally-scalable component. A
 * customer's socket is on instance A; the position is produced by tracking-service and lands
 * wherever. Redis Pub/Sub broadcasts it and every instance delivers to whichever of its own sockets
 * care — so the two never need to know about each other.
 *
 * <p>Subscribed by <em>pattern</em> rather than per-courier channel. With 300 couriers the
 * per-channel bookkeeping (subscribe on first interest, unsubscribe on last) costs more in
 * complexity than the filtering saves, and filtering happens against a hash lookup that is already
 * O(1). At the 30,000-courier tier this is the first thing to revisit.
 */
@Component
public class PositionFanoutBridge implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PositionFanoutBridge.class);
    private static final String CHANNEL_PATTERN = "loc:courier:*";

    private final SubscriptionRegistry registry;
    private final Counter delivered;
    private final Counter dropped;

    public PositionFanoutBridge(
            SubscriptionRegistry registry,
            RedisMessageListenerContainer container,
            MeterRegistry metrics) {
        this.registry = registry;
        this.delivered = metrics.counter("wassal_ws_frames_delivered_total");
        this.dropped = metrics.counter("wassal_ws_frames_dropped_total");
        container.addMessageListener(this, new PatternTopic(CHANNEL_PATTERN));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String courierId = channel.substring(channel.lastIndexOf(':') + 1);
        String payload = new String(message.getBody());

        for (WebSocketSession session : registry.subscribersOf(courierId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                // Synchronized because Spring's WebSocketSession is not safe for concurrent
                // sends, and Redis delivers on its own listener thread.
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                delivered.increment();
            } catch (IOException | IllegalStateException e) {
                // A slow or dead consumer must not stall fan-out for everyone else. Dropping is
                // correct for current-state data: the next position arrives in ~3s and is
                // fresher than the one that failed.
                dropped.increment();
                log.debug("Dropped frame for session {}", session.getId());
            }
        }
    }
}
