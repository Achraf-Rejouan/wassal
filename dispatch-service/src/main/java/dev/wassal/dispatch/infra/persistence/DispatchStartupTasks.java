package dev.wassal.dispatch.infra.persistence;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import dev.wassal.dispatch.infra.redis.RedisGeoCandidateFinder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovery work that runs once the service is up, plus the INV-4 gauge. */
@Component
public class DispatchStartupTasks {

    private static final Logger log = LoggerFactory.getLogger(DispatchStartupTasks.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final AvailabilitySet availability;
    private final RedisGeoCandidateFinder geoIndex;
    private final CancellationSaga sagas;
    private final AtomicInteger stuckOrders = new AtomicInteger();
    private final Duration dedupRetention;
    private final Duration kafkaRetention;
    private final Duration maxRetry;
    private final io.micrometer.core.instrument.Counter sagaResumeFailures;

    public DispatchStartupTasks(
            NamedParameterJdbcTemplate jdbc,
            AvailabilitySet availability,
            RedisGeoCandidateFinder geoIndex,
            CancellationSaga sagas,
            MeterRegistry metrics,
            @Value("${wassal.dedup.retention-hours:72}") long dedupRetentionHours,
            @Value("${wassal.kafka.retention-hours:24}") long kafkaRetentionHours,
            @Value("${wassal.retry.max-duration-hours:1}") long maxRetryHours) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.geoIndex = geoIndex;
        this.sagas = sagas;
        this.dedupRetention = Duration.ofHours(dedupRetentionHours);
        this.kafkaRetention = Duration.ofHours(kafkaRetentionHours);
        this.maxRetry = Duration.ofHours(maxRetryHours);
        metrics.gauge("wassal_orders_stuck_total", stuckOrders, AtomicInteger::get);
        this.sagaResumeFailures = metrics.counter("wassal_saga_resume_failed_total");
    }

    /**
     * S3-08. Dedup retention is a <em>correctness</em> constraint, not housekeeping: if a message
     * can be replayed from Kafka after its dedup record has been pruned, it will be processed twice
     * and INV-5 breaks. A config error here is silent and would surface weeks later as a mysterious
     * duplicate, so it fails startup instead.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void assertDedupRetentionIsSafe() {
        Duration required = kafkaRetention.plus(maxRetry);
        if (dedupRetention.compareTo(required) <= 0) {
            throw new IllegalStateException(
                    "Unsafe dedup retention: %s must exceed kafka retention %s + max retry %s = %s"
                            .formatted(dedupRetention, kafkaRetention, maxRetry, required));
        }
        log.info(
                "Dedup retention {} safely exceeds kafka {} + retry {}",
                dedupRetention,
                kafkaRetention,
                maxRetry);
    }

    /**
     * S3-13. Redis holds no durable state, so a restart leaves the geo index and availability set
     * empty and dispatch silently stops finding candidates. Rebuilding from Postgres — which is
     * authoritative — is a required feature, not an optimisation.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildGeoIndex() {
        List<Map<String, Object>> couriers =
                jdbc.queryForList(
                        "SELECT id, ST_Y(last_position::geometry) AS lat,"
                                + " ST_X(last_position::geometry) AS lon"
                                + " FROM dispatch.couriers"
                                + " WHERE status = 'AVAILABLE' AND last_position IS NOT NULL",
                        new MapSqlParameterSource());
        for (Map<String, Object> courier : couriers) {
            CourierId id = CourierId.of((UUID) courier.get("id"));
            geoIndex.upsertPosition(
                    id,
                    new GeoPoint(
                            ((Number) courier.get("lat")).doubleValue(),
                            ((Number) courier.get("lon")).doubleValue()));
            availability.markAvailable(id);
        }
        log.info("Rebuilt geo index and availability set with {} couriers", couriers.size());
    }

    /**
     * ADR-0008's resumption, run once the context is ready.
     *
     * <p>Failures here are logged and counted rather than propagated. A saga that cannot resume is
     * serious, but refusing to start would be worse: the service would stop accepting orders
     * entirely over a problem affecting a handful of in-flight cancellations. The stuck-order gauge
     * and the reconciliation job are what catch the residue.
     *
     * <p>The dedup-retention assertion above deliberately does <em>not</em> follow this rule. A
     * wrong retention window silently double-processes messages, and a service that boots with
     * INV-5 broken is worse than one that refuses to boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeSagas() {
        try {
            int resumed = sagas.resumeInFlight();
            if (resumed > 0) {
                log.warn("Resumed {} in-flight cancellation sagas after restart", resumed);
            }
        } catch (RuntimeException e) {
            sagaResumeFailures.increment();
            log.error("Failed to resume in-flight sagas on startup", e);
        }
    }

    /**
     * S3-06. INV-4's failure condition made observable: an order past its SLA with no terminal
     * state. The equivalence between {@code terminal_at IS NULL} and being non-terminal is enforced
     * by {@code chk_terminal_consistency} in the orders schema, which is what makes this a cheap
     * indexed query rather than a scan.
     *
     * <p>Read from the dispatch side via offers, since dispatch cannot read the orders table.
     */
    @Scheduled(fixedDelay = 30_000)
    public void reportStuckOrders() {
        Integer stuck =
                jdbc.queryForObject(
                        """
                        SELECT count(DISTINCT o.order_id)
                          FROM dispatch.offers o
                         WHERE o.status = 'OFFERED'
                           AND o.expires_at < now() - interval '5 minutes'
                        """,
                        new MapSqlParameterSource(),
                        Integer.class);
        stuckOrders.set(stuck == null ? 0 : stuck);
        if (stuckOrders.get() > 0) {
            log.error("INV-4 risk: {} offers overdue by more than 5 minutes", stuckOrders.get());
        }
    }
}
