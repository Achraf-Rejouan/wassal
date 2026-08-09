package dev.wassal.order.infra.persistence;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * INV-4 made observable at runtime (S3-06, FR-015).
 *
 * <p>INV-4 is the one invariant that cannot be enforced by a constraint: it is a property of a
 * process over <em>time</em> ("reaches a terminal state") rather than of a row, so the only way to
 * know it is holding is to keep asking.
 *
 * <p>The query is cheap because {@code chk_terminal_consistency} guarantees {@code terminal_at IS
 * NULL} is exactly equivalent to being non-terminal, which lets the partial index {@code
 * idx_orders_stuck} answer it. If that equivalence ever drifted, this gauge would silently stop
 * measuring the thing it claims to measure — which is why the constraint exists.
 */
@Component
public class StuckOrderGauge {

    private static final Logger log = LoggerFactory.getLogger(StuckOrderGauge.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final AtomicInteger stuck = new AtomicInteger();

    public StuckOrderGauge(NamedParameterJdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        metrics.gauge("wassal_orders_stuck_total", stuck, AtomicInteger::get);
    }

    @Scheduled(fixedDelayString = "${wassal.stuck-gauge.interval-ms:30000}")
    public void measure() {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM orders.orders"
                                + " WHERE terminal_at IS NULL AND sla_deadline < now()",
                        new MapSqlParameterSource(),
                        Integer.class);
        stuck.set(count == null ? 0 : count);
        if (stuck.get() > 0) {
            log.error(
                    "INV-4 VIOLATION: {} orders past their SLA with no terminal state",
                    stuck.get());
        }
    }

    public int current() {
        return stuck.get();
    }
}
