package dev.wassal.order.infra.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polling outbox publisher (ADR-0006).
 *
 * <p>CDC was deferred, not rejected: this table's column shape matches Debezium's
 * outbox-event-router SMT, so switching later is Connect configuration plus deleting this class.
 * The 100 ms poll is the largest single line in the assignment latency budget and is the first
 * lever if p99 misses.
 *
 * <p>Two properties carry INV-5. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so multiple
 * instances can publish concurrently without coordination. And {@code sent_at} is stamped <em>only
 * after</em> the broker acknowledges — a crash between publish and mark republishes on recovery,
 * which is at-least-once delivery that consumer-side dedup turns into an effectively-once effect.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(
            NamedParameterJdbcTemplate jdbc,
            KafkaTemplate<String, String> kafka,
            MeterRegistry metrics,
            @Value("${wassal.outbox.topic}") String topic,
            @Value("${wassal.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${wassal.outbox.poll-interval-ms:100}")
    @Transactional
    public void publishPending() {
        List<Map<String, Object>> pending =
                jdbc.queryForList(
                        """
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at
                          FROM orders.order_outbox
                         WHERE sent_at IS NULL
                         ORDER BY aggregate_id, created_at
                         LIMIT :batchSize
                           FOR UPDATE SKIP LOCKED
                        """,
                        new MapSqlParameterSource("batchSize", batchSize));

        if (pending.isEmpty()) {
            return;
        }

        for (Map<String, Object> row : pending) {
            UUID id = (UUID) row.get("id");
            UUID aggregateId = (UUID) row.get("aggregate_id");
            String eventType = (String) row.get("event_type");
            String payload = (String) row.get("payload");

            var record =
                    new org.apache.kafka.clients.producer.ProducerRecord<String, String>(
                            topic, aggregateId.toString(), payload);
            // The message id is what consumers dedup on — it must survive the hop.
            record.headers().add("message-id", id.toString().getBytes());
            record.headers().add("event-type", eventType.getBytes());

            try {
                // Blocking send: sent_at must not be stamped before the broker has the record.
                kafka.send(record).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted publishing outbox row " + id, e);
            } catch (Exception e) {
                // Left unsent deliberately — the next poll retries it. Never swallowed.
                log.error("Failed to publish outbox row {}, will retry", id, e);
                throw new IllegalStateException("Outbox publish failed for " + id, e);
            }

            jdbc.update(
                    "UPDATE orders.order_outbox SET sent_at = now(), attempts = attempts + 1"
                            + " WHERE id = :id",
                    new MapSqlParameterSource("id", id));

            Instant createdAt = ((java.sql.Timestamp) row.get("created_at")).toInstant();
            metrics.timer("wassal_outbox_publish_lag")
                    .record(Duration.between(createdAt, Instant.now()));
        }

        log.debug("Published {} outbox rows", pending.size());
    }

    /** Depth is the earliest and most reliable signal that Kafka is in trouble. */
    @Scheduled(fixedDelay = 5000)
    public void reportDepth() {
        Integer depth =
                jdbc.queryForObject(
                        "SELECT count(*) FROM orders.order_outbox WHERE sent_at IS NULL",
                        new MapSqlParameterSource(),
                        Integer.class);
        metrics.gauge("wassal_outbox_depth", depth == null ? 0 : depth);
    }
}
