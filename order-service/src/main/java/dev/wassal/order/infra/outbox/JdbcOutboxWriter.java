package dev.wassal.order.infra.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wassal.order.domain.port.OutboxWriter;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes a domain event to the outbox (FR-013, INV-5).
 *
 * <p>There is no {@code @Transactional} here on purpose. This must join the caller's transaction —
 * the one that also wrote the state change — so that rolling back leaves no event and committing
 * cannot leave an event behind. Starting its own transaction would reintroduce the dual-write the
 * outbox exists to eliminate.
 */
@Component
public class JdbcOutboxWriter implements OutboxWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String sql =
                """
                INSERT INTO orders.order_outbox (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:aggregateType, :aggregateId, :eventType, :payload)
                """;
        jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("aggregateType", aggregateType)
                        .addValue("aggregateId", aggregateId)
                        .addValue("eventType", eventType)
                        .addValue("payload", toJsonb(payload)));
    }

    private PGobject toJsonb(Object payload) {
        try {
            PGobject json = new PGobject();
            json.setType("jsonb");
            json.setValue(objectMapper.writeValueAsString(payload));
            return json;
        } catch (JsonProcessingException | java.sql.SQLException e) {
            // Never swallowed: an unserialisable event means the state change must not commit.
            throw new IllegalStateException("Failed to serialise outbox payload", e);
        }
    }
}
