package dev.wassal.dispatch.infra.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dispatch-side outbox. No {@code @Transactional} — it must join the caller's transaction so that
 * an assignment without its event, or an event without its assignment, is unreachable.
 */
@Component
public class DispatchOutboxWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DispatchOutboxWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        jdbc.update(
                """
                INSERT INTO dispatch.dispatch_outbox
                       (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:aggregateType, :aggregateId, :eventType, :payload)
                """,
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise outbox payload", e);
        }
    }
}
