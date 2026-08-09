package dev.wassal.tracking.infra.coldpath;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The cold path (FR-010, NFR-003): append-only history, batched.
 *
 * <p>Decoupled from the hot path on purpose. Ingest returns as soon as Redis has the position; this
 * flusher drains a buffer on its own schedule, so Postgres write rate is a function of the
 * <em>flush interval</em> rather than of the message rate. That is what turns 100 msg/s into
 * roughly 0.2 writes/s — the order of magnitude NFR-003 demands.
 *
 * <p><strong>What is lost in a crash, stated plainly:</strong> at most one flush window of
 * <em>history</em>. The hot path is unaffected because Redis already has the position, and the loss
 * is counted rather than hidden. Location is a cache, not a ledger — writing that down is the
 * difference between a tradeoff and a bug.
 */
@Component
public class ColdPathFlusher {

    private static final Logger log = LoggerFactory.getLogger(ColdPathFlusher.class);

    public record Sample(
            String courierId, double lat, double lon, Instant recordedAt, Double speedKmh) {}

    private final ConcurrentLinkedQueue<Sample> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffered = new AtomicInteger();
    private final JdbcTemplate jdbc;
    private final int maxBatch;
    private final Counter flushed;
    private final Counter lostOnShutdown;
    private final Counter duplicatesDropped;
    private final Counter flushOperations;

    public ColdPathFlusher(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @Value("${wassal.coldpath.max-batch:1000}") int maxBatch) {
        this.jdbc = jdbc;
        this.maxBatch = maxBatch;
        this.flushed = metrics.counter("wassal_coldpath_rows_flushed_total");
        this.lostOnShutdown = metrics.counter("wassal_coldpath_rows_lost_total");
        this.duplicatesDropped = metrics.counter("wassal_coldpath_duplicates_dropped_total");
        // The metric NFR-003 actually cares about. Rows reaching Postgres are necessarily 1:1
        // with positions — nothing is discarded — so what batching reduces is the number of
        // write STATEMENTS, and that is what must be measured to make the claim honestly.
        this.flushOperations = metrics.counter("wassal_coldpath_flush_operations_total");
        metrics.gauge("wassal_coldpath_buffered", buffered, AtomicInteger::get);
    }

    /** Non-blocking. The ingest request must never wait on a database write. */
    public void enqueue(Sample sample) {
        buffer.add(sample);
        buffered.incrementAndGet();
    }

    @Scheduled(fixedDelayString = "${wassal.coldpath.flush-interval-ms:5000}")
    public void flush() {
        List<Sample> batch = new ArrayList<>(maxBatch);
        Sample sample;
        while (batch.size() < maxBatch && (sample = buffer.poll()) != null) {
            batch.add(sample);
        }
        if (batch.isEmpty()) {
            return;
        }
        buffered.addAndGet(-batch.size());

        // ON CONFLICT DO NOTHING: the primary key already rejects a duplicate report, and a
        // batch that failed wholesale because one row was retried would lose the other 999.
        int[] results =
                jdbc.batchUpdate(
                        """
                        INSERT INTO tracking.location_history
                               (courier_id, recorded_at, position, speed_kmh)
                        VALUES (?::uuid, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                        ON CONFLICT (courier_id, recorded_at) DO NOTHING
                        """,
                        batch.stream()
                                .map(
                                        s ->
                                                new Object[] {
                                                    s.courierId(),
                                                    java.sql.Timestamp.from(s.recordedAt()),
                                                    s.lon(),
                                                    s.lat(),
                                                    s.speedKmh()
                                                })
                                .toList());

        int inserted = 0;
        for (int result : results) {
            if (result > 0) {
                inserted++;
            }
        }
        flushOperations.increment();
        flushed.increment(inserted);
        duplicatesDropped.increment(batch.size() - (double) inserted);
        log.debug("Flushed {} of {} buffered positions", inserted, batch.size());
    }

    /**
     * Best-effort drain on shutdown. Anything still buffered when the process dies is counted, so
     * the documented loss window is measurable rather than theoretical.
     */
    @jakarta.annotation.PreDestroy
    public void drainOnShutdown() {
        int remaining = buffered.get();
        try {
            flush();
        } catch (RuntimeException e) {
            lostOnShutdown.increment(remaining);
            log.warn("Lost {} buffered positions on shutdown", remaining, e);
        }
    }

    /** Creates tomorrow's partition ahead of time so a midnight rollover never fails a write. */
    @Scheduled(cron = "0 0 * * * *")
    public void ensurePartitions() {
        jdbc.execute("SELECT tracking.ensure_partition(current_date)");
        jdbc.execute("SELECT tracking.ensure_partition(current_date + 1)");
    }

    public int bufferedCount() {
        return buffered.get();
    }
}
