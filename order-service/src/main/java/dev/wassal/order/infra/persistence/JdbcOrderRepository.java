package dev.wassal.order.infra.persistence;

import dev.wassal.order.domain.model.CourierId;
import dev.wassal.order.domain.model.GeoPoint;
import dev.wassal.order.domain.model.MerchantId;
import dev.wassal.order.domain.model.Order;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.model.OrderStatus;
import dev.wassal.order.domain.port.OrderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Postgres adapter for {@link OrderRepository}.
 *
 * <p>Plain SQL rather than JPA, deliberately: the transitions here are <em>conditional updates</em>
 * whose affected-row count is the decision, and an ORM's dirty-checking would turn that into a
 * read-modify-write — the exact check-then-act this project exists to disprove. Every statement is
 * parameterised; no string concatenation reaches a query.
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Order order) {
        String sql =
                """
                INSERT INTO orders.orders (
                    id, merchant_id, status, pickup, dropoff, offer_attempts,
                    created_at, sla_deadline)
                VALUES (
                    :id, :merchantId, CAST(:status AS orders.order_status),
                    ST_SetSRID(ST_MakePoint(:pickupLon, :pickupLat), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:dropoffLon, :dropoffLat), 4326)::geography,
                    :offerAttempts, :createdAt, :slaDeadline)
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("id", order.id().value())
                        .addValue("merchantId", order.merchantId().value())
                        .addValue("status", order.status().name())
                        .addValue("pickupLat", order.pickup().lat())
                        .addValue("pickupLon", order.pickup().lon())
                        .addValue("dropoffLat", order.dropoff().lat())
                        .addValue("dropoffLon", order.dropoff().lon())
                        .addValue("offerAttempts", order.offerAttempts())
                        .addValue("createdAt", java.sql.Timestamp.from(order.createdAt()))
                        .addValue("slaDeadline", java.sql.Timestamp.from(order.slaDeadline()));
        jdbc.update(sql, params);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        String sql =
                """
                SELECT id, merchant_id, status::text AS status,
                       ST_Y(pickup::geometry)  AS pickup_lat,
                       ST_X(pickup::geometry)  AS pickup_lon,
                       ST_Y(dropoff::geometry) AS dropoff_lat,
                       ST_X(dropoff::geometry) AS dropoff_lon,
                       assigned_courier_id, offer_attempts, created_at, terminal_at, version
                  FROM orders.orders
                 WHERE id = :id
                """;
        return jdbc.query(sql, new MapSqlParameterSource("id", id.value()), this::mapRow).stream()
                .findFirst();
    }

    /**
     * The conditional transition. Exactly one row moves from {@code expected} to {@code target}, or
     * none does and the caller has lost the race.
     *
     * <p>{@code terminal_at} is set in the same statement, because {@code chk_terminal_consistency}
     * makes the two inseparable — setting the status without the stamp would be rejected by the
     * database, which is the point of having the constraint.
     */
    @Override
    public boolean applyTransition(OrderId id, OrderStatus expected, OrderStatus target) {
        String sql =
                """
                UPDATE orders.orders
                   SET status      = CAST(:target AS orders.order_status),
                       terminal_at = CASE WHEN :isTerminal THEN now() ELSE NULL END,
                       updated_at  = now(),
                       version     = version + 1
                 WHERE id     = :id
                   AND status = CAST(:expected AS orders.order_status)
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("id", id.value())
                        .addValue("expected", expected.name())
                        .addValue("target", target.name())
                        .addValue("isTerminal", target.isTerminal());
        return jdbc.update(sql, params) == 1;
    }

    private Order mapRow(ResultSet rs, int rowNum) throws SQLException {
        var terminalAt = rs.getTimestamp("terminal_at");
        var courierId = rs.getObject("assigned_courier_id", java.util.UUID.class);
        return Order.rehydrate(
                OrderId.of(rs.getObject("id", java.util.UUID.class)),
                MerchantId.of(rs.getObject("merchant_id", java.util.UUID.class)),
                new GeoPoint(rs.getDouble("pickup_lat"), rs.getDouble("pickup_lon")),
                new GeoPoint(rs.getDouble("dropoff_lat"), rs.getDouble("dropoff_lon")),
                rs.getTimestamp("created_at").toInstant(),
                OrderStatus.valueOf(rs.getString("status")),
                courierId == null ? null : CourierId.of(courierId),
                rs.getInt("offer_attempts"),
                terminalAt == null ? null : terminalAt.toInstant(),
                rs.getLong("version"));
    }
}
