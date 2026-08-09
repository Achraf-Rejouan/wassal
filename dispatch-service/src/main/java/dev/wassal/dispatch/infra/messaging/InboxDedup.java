package dev.wassal.dispatch.infra.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumer-side dedup — the mechanism that turns Kafka's at-least-once delivery into an
 * effectively-once business effect (FR-014, INV-5).
 *
 * <p>The dedup row is inserted in the <em>same transaction</em> as the effect, which makes "dedup
 * record exists but effect did not commit" an unreachable state rather than a case to handle.
 * Concurrent duplicates race on the primary key: one commits, the other catches the violation and
 * takes the no-op branch. That is why this returns a boolean rather than checking first — a
 * lookup-then-insert would have a window between the two.
 */
@Component
public class InboxDedup {

    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public InboxDedup(NamedParameterJdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /**
     * @return true if this caller claimed the message and must perform the effect; false if it was
     *     already processed, in which case the caller must do nothing
     */
    public boolean claim(UUID messageId, String consumerGroup) {
        try {
            int inserted =
                    jdbc.update(
                            """
                            INSERT INTO dispatch.processed_messages (message_id, consumer_group)
                            VALUES (:messageId, :consumerGroup)
                            ON CONFLICT (message_id, consumer_group) DO NOTHING
                            """,
                            new MapSqlParameterSource()
                                    .addValue("messageId", messageId)
                                    .addValue("consumerGroup", consumerGroup));
            if (inserted == 0) {
                metrics.counter("wassal_duplicate_suppressed_total").increment();
                return false;
            }
            return true;
        } catch (DuplicateKeyException e) {
            // Concurrent duplicate lost the race. Expected under redelivery, not an error.
            metrics.counter("wassal_duplicate_suppressed_total").increment();
            return false;
        }
    }
}
