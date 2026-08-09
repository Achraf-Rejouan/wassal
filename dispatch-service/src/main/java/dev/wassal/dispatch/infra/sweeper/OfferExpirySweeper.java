package dev.wassal.dispatch.infra.sweeper;

import dev.wassal.contracts.assignment.OfferExpired;
import dev.wassal.dispatch.infra.outbox.DispatchOutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable offer expiry (ADR-0005, FR-011). The mechanism INV-4 rests on.
 *
 * <p>The deadline lives in {@code offers.expires_at}, written in the same transaction as the offer.
 * An in-process timer would die with the process and strand the order forever; this sweeper simply
 * finds whatever is overdue when it next runs, so a restart spanning a deadline changes
 * <em>when</em> expiry fires, never <em>whether</em> it does.
 *
 * <p><strong>Every time comparison uses database {@code now()}.</strong> Never the JVM clock. Two
 * sweeper instances on hosts with drifting clocks would otherwise disagree about which offers are
 * overdue, and the accept path compares against the same column — so a JVM-side comparison here
 * would reintroduce exactly the skew that using database time eliminates.
 *
 * <p><strong>No leader election.</strong> {@code FOR UPDATE SKIP LOCKED} means several instances
 * can sweep concurrently and each overdue offer is claimed exactly once. Adding a coordination
 * mechanism to a problem that does not need one would be the decorative kind of complexity ADR-0001
 * forbids.
 */
@Component
public class OfferExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OfferExpirySweeper.class);

    /** Ceiling for the adaptive batch (architecture review F-4). */
    private static final int MAX_BATCH = 2_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final DispatchOutboxWriter outbox;
    private final ReofferPort reoffer;

    private final AtomicInteger batchSize;
    private final AtomicLong lagMillis = new AtomicLong();

    public OfferExpirySweeper(
            NamedParameterJdbcTemplate jdbc,
            DispatchOutboxWriter outbox,
            ReofferPort reoffer,
            MeterRegistry metrics,
            @Value("${wassal.sweeper.batch-size:500}") int initialBatch) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.reoffer = reoffer;
        this.batchSize = new AtomicInteger(initialBatch);

        // Lag is the signal that matters. The alert threshold is 0.5s — deliberately BELOW
        // NFR-004's ±1s requirement, so the alert fires before the SLO breaks rather than at
        // the same moment (architecture review F-4).
        metrics.gauge("wassal_sweeper_lag_seconds", lagMillis, l -> l.get() / 1000.0);
        metrics.gauge("wassal_sweeper_batch_size", batchSize, AtomicInteger::get);
    }

    /**
     * 250 ms tick against a ±1 s requirement — 4× headroom for scheduling jitter and long batches.
     * Tightening it further would buy accuracy nobody asked for at the cost of more empty queries,
     * which are already the common case.
     */
    @Scheduled(fixedDelayString = "${wassal.sweeper.interval-ms:250}")
    @Transactional
    public void sweep() {
        List<Map<String, Object>> expired =
                jdbc.queryForList(
                        """
                        UPDATE dispatch.offers
                           SET status = 'EXPIRED', responded_at = now()
                         WHERE id IN (
                               SELECT id FROM dispatch.offers
                                WHERE status = 'OFFERED' AND expires_at <= now()
                                ORDER BY expires_at
                                LIMIT :batchSize
                                  FOR UPDATE SKIP LOCKED)
                        RETURNING id, order_id, courier_id, expires_at,
                                  EXTRACT(EPOCH FROM (now() - expires_at)) * 1000 AS lag_ms
                        """,
                        new MapSqlParameterSource("batchSize", batchSize.get()));

        if (expired.isEmpty()) {
            lagMillis.set(0);
            adaptBatch(0);
            return;
        }

        double worstLag = 0;
        for (Map<String, Object> row : expired) {
            UUID offerId = (UUID) row.get("id");
            UUID orderId = (UUID) row.get("order_id");
            UUID courierId = (UUID) row.get("courier_id");
            Instant deadline = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
            double lag = ((Number) row.get("lag_ms")).doubleValue();
            worstLag = Math.max(worstLag, lag);

            outbox.write(
                    "Offer",
                    offerId,
                    "OfferExpired",
                    new OfferExpired(offerId, orderId, courierId, deadline, Instant.now()));

            // Re-offer in-process. The offer aggregate belongs to this service, so routing the
            // control flow through Kafka would add the publisher poll plus consume latency to
            // FR-007's 200 ms re-offer budget for no isolation benefit (review F-8).
            reoffer.reofferAfterFailedAttempt(orderId);
        }

        lagMillis.set((long) worstLag);
        adaptBatch(expired.size());

        log.info("Expired {} offers, worst lag {}ms", expired.size(), Math.round(worstLag));
    }

    /**
     * Grows the batch while the sweeper is behind and shrinks it when idle.
     *
     * <p>A fixed batch of 100 caps expiry at 400/s, and the stress profile deliberately creates
     * mass simultaneous expiry — at 1,000 concurrent expiries a fixed batch takes 2.5 s and
     * breaches NFR-004's ±1 s. Adapting absorbs the burst without paying for a large batch in
     * steady state, where almost every sweep returns nothing.
     */
    private void adaptBatch(int swept) {
        int current = batchSize.get();
        if (lagMillis.get() > 250) {
            batchSize.set(Math.min(MAX_BATCH, current * 2));
        } else if (swept == 0 && current > 500) {
            batchSize.set(Math.max(500, current / 2));
        }
    }

    public long currentLagMillis() {
        return lagMillis.get();
    }

    /**
     * Re-offer callback, declared here rather than imported, so the sweeper does not depend on the
     * dispatcher's concrete type and the two can be tested apart.
     */
    public interface ReofferPort {
        void reofferAfterFailedAttempt(UUID orderId);
    }
}
