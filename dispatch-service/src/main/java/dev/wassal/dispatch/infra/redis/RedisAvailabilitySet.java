package dev.wassal.dispatch.infra.redis;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Dispatch-owned set of AVAILABLE courier ids (architecture review F-3). */
@Component
public class RedisAvailabilitySet implements AvailabilitySet {

    public static final String KEY = "set:available";

    private final StringRedisTemplate redis;

    public RedisAvailabilitySet(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void markAvailable(CourierId courierId) {
        redis.opsForSet().add(KEY, courierId.toString());
    }

    @Override
    public void markUnavailable(CourierId courierId) {
        redis.opsForSet().remove(KEY, courierId.toString());
    }

    @Override
    public boolean contains(CourierId courierId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY, courierId.toString()));
    }

    @Override
    public void publishAssignment(String orderId, String courierId) {
        // TTL rather than explicit deletion: the mapping is a lookup aid, and an order that
        // never completes should not leak a key forever.
        redis.opsForValue()
                .set("assign:order:" + orderId, courierId, java.time.Duration.ofHours(6));
    }

    @Override
    public long size() {
        Long size = redis.opsForSet().size(KEY);
        return size == null ? 0 : size;
    }
}
