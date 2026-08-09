package dev.wassal.gateway.subscription;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Which sockets on <em>this instance</em> care about which courier.
 *
 * <p>This is the only state the gateway holds, and it is per-instance and disposable — which is
 * exactly what makes gateway instances interchangeable. Persisting it would forfeit the property
 * the Redis Pub/Sub layer exists to provide.
 */
@Component
public class SubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistry.class);

    private final Map<String, Set<WebSocketSession>> byCourier = new ConcurrentHashMap<>();
    private final Map<String, String> courierOfSession = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCount = new AtomicInteger();
    private final int globalCap;

    public SubscriptionRegistry(
            MeterRegistry metrics, @Value("${wassal.ws.global-cap:2000}") int globalCap) {
        this.globalCap = globalCap;
        metrics.gauge("wassal_ws_sessions", sessionCount, AtomicInteger::get);
        metrics.gauge("wassal_ws_subscribed_couriers", byCourier, Map::size);
    }

    /**
     * @return false if the global cap is reached — a 503 on upgrade, not a silent accept
     */
    public boolean register(WebSocketSession session, String courierId) {
        if (sessionCount.get() >= globalCap) {
            return false;
        }
        byCourier.computeIfAbsent(courierId, k -> ConcurrentHashMap.newKeySet()).add(session);
        courierOfSession.put(session.getId(), courierId);
        sessionCount.incrementAndGet();
        log.debug("Session {} subscribed to courier {}", session.getId(), courierId);
        return true;
    }

    public void unregister(WebSocketSession session) {
        String courierId = courierOfSession.remove(session.getId());
        if (courierId == null) {
            return;
        }
        Set<WebSocketSession> sessions = byCourier.get(courierId);
        if (sessions != null) {
            sessions.remove(session);
            // Drop the key when the last subscriber goes, so fan-out stays proportional to
            // interest rather than to total traffic.
            if (sessions.isEmpty()) {
                byCourier.remove(courierId);
            }
        }
        sessionCount.decrementAndGet();
    }

    public Set<WebSocketSession> subscribersOf(String courierId) {
        return byCourier.getOrDefault(courierId, Set.of());
    }

    public Set<String> subscribedCouriers() {
        return byCourier.keySet();
    }

    public int sessions() {
        return sessionCount.get();
    }
}
