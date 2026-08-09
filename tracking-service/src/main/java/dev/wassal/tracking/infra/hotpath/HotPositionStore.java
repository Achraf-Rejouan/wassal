package dev.wassal.tracking.infra.hotpath;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The hot path (FR-010, NFR-003): last-known position, overwritten, never queued.
 *
 * <p>At 100 msg/s a naive write to Postgres would be 100 heap updates plus index maintenance plus
 * WAL per second — the write amplification challenge 3.4 exists to avoid. This path never touches
 * Postgres at all.
 *
 * <p><strong>Out-of-order reports are the subtle part.</strong> Mobile networks reorder, so a stale
 * position can arrive after a fresh one. Comparing timestamps in Java would be a check-then-act:
 * two concurrent reports could both read the old value and both decide they are newer. The
 * comparison therefore happens <em>inside Redis</em> as a Lua script, which executes atomically —
 * the same "let the store arbitrate" reasoning as ADR-0004, on a cache instead of a claim.
 */
@Component
public class HotPositionStore {

    public static final String GEO_KEY = "geo:couriers";

    /**
     * Applies a position only if it is newer than what is stored. Returns 1 if applied, 0 if a
     * fresher position was already present.
     *
     * <p>KEYS[1] hot hash, KEYS[2] geo key. ARGV: courierId, lat, lon, recordedAtEpochMillis.
     */
    private static final RedisScript<Long> APPLY_IF_NEWER =
            new DefaultRedisScript<>(
                    """
                    local stored = redis.call('HGET', KEYS[1], 'recordedAt')
                    if stored and tonumber(stored) >= tonumber(ARGV[4]) then
                      return 0
                    end
                    redis.call('HSET', KEYS[1], 'lat', ARGV[2], 'lon', ARGV[3],
                               'recordedAt', ARGV[4])
                    redis.call('EXPIRE', KEYS[1], 300)
                    redis.call('GEOADD', KEYS[2], ARGV[3], ARGV[2], ARGV[1])
                    return 1
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final Counter applied;
    private final Counter rejectedStale;

    public HotPositionStore(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.applied = metrics.counter("wassal_position_applied_total");
        this.rejectedStale = metrics.counter("wassal_position_rejected_stale_total");
    }

    /**
     * @return true if this report was newer and was applied
     */
    public boolean apply(String courierId, double lat, double lon, long recordedAtEpochMillis) {
        Long result =
                redis.execute(
                        APPLY_IF_NEWER,
                        List.of(hotKey(courierId), GEO_KEY),
                        courierId,
                        Double.toString(lat),
                        Double.toString(lon),
                        Long.toString(recordedAtEpochMillis));
        boolean wasApplied = result != null && result == 1L;
        if (wasApplied) {
            applied.increment();
        } else {
            rejectedStale.increment();
        }
        return wasApplied;
    }

    public Point read(String courierId) {
        var hash = redis.opsForHash().entries(hotKey(courierId));
        if (hash.isEmpty()) {
            return null;
        }
        return new Point(
                Double.parseDouble((String) hash.get("lon")),
                Double.parseDouble((String) hash.get("lat")));
    }

    private static String hotKey(String courierId) {
        return "hot:pos:" + courierId;
    }
}
