package dev.wassal.dispatch.infra.redis;

import dev.wassal.dispatch.domain.model.Candidate;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.domain.port.CandidateFinder;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Candidate search over the Redis geo index, intersected with the availability set (ADR-0003,
 * architecture review F-3).
 *
 * <p>Two structures with distinct owners: {@code geo:couriers} holds <em>every</em> courier's
 * position, {@code set:available} holds who is free. Searching the geo index and filtering against
 * the set keeps the ownership clean — tracking owns <em>where</em>, dispatch owns <em>who is
 * free</em> — and at N=300 the intersection is free.
 *
 * <p>Redis rather than PostGIS because the workload is roughly 40:1 write-to-read: 100 position
 * updates per second against a handful of searches. PostGIS would mean 100 GiST index updates per
 * second, which fails NFR-003 arithmetically rather than marginally.
 */
@Component
public class RedisGeoCandidateFinder implements CandidateFinder {

    /**
     * Written by tracking-service from Sprint 4 (S4-05). Until then dispatch seeds it on the
     * availability toggle, which is a temporary ownership overlap — the read side below is already
     * correct and does not change when the writer moves.
     */
    public static final String GEO_KEY = "geo:couriers";

    private final StringRedisTemplate redis;

    public RedisGeoCandidateFinder(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<Candidate> findNearestAvailable(
            OrderId orderId,
            GeoPoint around,
            double radiusMetres,
            int limit,
            List<String> excludeAlreadyOffered) {

        var args =
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending();

        var results =
                redis.opsForGeo()
                        .radius(
                                GEO_KEY,
                                new Circle(
                                        new Point(around.lon(), around.lat()),
                                        // Spring Data has no METERS metric; km in, metres out.
                                        new Distance(radiusMetres / 1000.0, Metrics.KILOMETERS)),
                                args);

        if (results == null) {
            return List.of();
        }

        Set<String> available = redis.opsForSet().members(RedisAvailabilitySet.KEY);
        if (available == null || available.isEmpty()) {
            return List.of();
        }
        Set<String> excluded = Set.copyOf(excludeAlreadyOffered);

        return results.getContent().stream()
                .map(
                        result ->
                                new Candidate(
                                        CourierId.of(
                                                UUID.fromString(result.getContent().getName())),
                                        result.getDistance().getValue() * 1000.0))
                .filter(candidate -> available.contains(candidate.courierId().toString()))
                .filter(candidate -> !excluded.contains(candidate.courierId().toString()))
                // Ties at equal distance break by id, not by Redis iteration order. Without this
                // a seeded simulator run would not reproduce, and NFR-008 requires that it does.
                .sorted(
                        Comparator.comparingDouble(Candidate::distanceMetres)
                                .thenComparing(c -> c.courierId().toString()))
                .limit(limit)
                .toList();
    }

    /** Seeds a courier's position. Moves to tracking-service in Sprint 4. */
    public void upsertPosition(CourierId courierId, GeoPoint position) {
        redis.opsForGeo()
                .add(GEO_KEY, new Point(position.lon(), position.lat()), courierId.toString());
    }
}
