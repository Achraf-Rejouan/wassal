package dev.wassal.tracking.infra.fanout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fan-out to WebSocket clients via Redis Pub/Sub (ADR-0007, challenge 3.5).
 *
 * <p>Positions go to Redis Pub/Sub rather than Kafka, and that is a guarantee chosen per data class
 * rather than per system. A position is <em>current state</em> with a ~3 s useful life: losing one
 * is invisible because the next arrives shortly, so at-most-once is the correct guarantee. Kafka
 * would spend durability, ordering and replay on data whose value expires in three seconds — and
 * would require per-instance consumer groups for gateway processes designed to be disposable.
 *
 * <p>Channel is per <em>courier</em>, not per order. Tracking knows which courier reported; it does
 * not know about assignments, and reaching for that would cross a module boundary. The gateway
 * resolves order → courier and subscribes accordingly.
 */
@Component
public class PositionPublisher {

    private final StringRedisTemplate redis;
    private final Counter published;

    public PositionPublisher(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.published = metrics.counter("wassal_position_published_total");
    }

    public static String channelFor(String courierId) {
        return "loc:courier:" + courierId;
    }

    public void publish(String courierId, double lat, double lon, long recordedAtEpochMillis) {
        String payload =
                "{\"courierId\":\"%s\",\"lat\":%s,\"lon\":%s,\"recordedAt\":%d}"
                        .formatted(courierId, lat, lon, recordedAtEpochMillis);
        redis.convertAndSend(channelFor(courierId), payload);
        published.increment();
    }
}
