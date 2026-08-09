package dev.wassal.order.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wassal.order.domain.model.MerchantId;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.port.IdempotencyKeyReusedException;
import dev.wassal.order.domain.port.IdempotencyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency via the primary key, not via a prior lookup.
 *
 * <p>{@code ON CONFLICT DO NOTHING} plus an affected-row check is the whole mechanism: the database
 * arbitrates concurrent duplicates, so there is no window between deciding and acting. This is the
 * same shape as the atomic claim in Sprint 2, on an easier problem.
 */
@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Claim claim(MerchantId merchantId, String key, Object request, OrderId orderId) {
        String hash = hash(request);

        String insert =
                """
                INSERT INTO orders.idempotency_keys (merchant_id, key, request_hash, order_id)
                VALUES (:merchantId, :key, :hash, :orderId)
                ON CONFLICT (merchant_id, key) DO NOTHING
                """;
        int inserted =
                jdbc.update(
                        insert,
                        new MapSqlParameterSource()
                                .addValue("merchantId", merchantId.value())
                                .addValue("key", key)
                                .addValue("hash", hash)
                                .addValue("orderId", orderId.value()));

        if (inserted == 1) {
            return Claim.claimed();
        }

        Map<String, Object> existing =
                jdbc.queryForMap(
                        """
                        SELECT order_id, request_hash
                          FROM orders.idempotency_keys
                         WHERE merchant_id = :merchantId AND key = :key
                        """,
                        new MapSqlParameterSource()
                                .addValue("merchantId", merchantId.value())
                                .addValue("key", key));

        if (!hash.equals(existing.get("request_hash"))) {
            // Same key, different payload. Returning the original result here would silently
            // give the client an order they did not ask for.
            throw new IdempotencyKeyReusedException(key);
        }
        return Claim.alreadyClaimedBy(OrderId.of((UUID) existing.get("order_id")));
    }

    private String hash(Object request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to hash idempotency request", e);
        }
    }
}
