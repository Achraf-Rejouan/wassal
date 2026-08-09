package dev.wassal.dispatch.infra.persistence;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Offer persistence. The deadline is a column, which is what makes FR-012 resolvable. */
@Repository
public class OfferRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OfferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates an offer with its deadline computed by the <em>database</em> clock, not the JVM's.
     *
     * <p>The TTL is converted in <strong>milliseconds</strong>. Seconds truncate a sub-second TTL
     * to zero, which makes {@code expires_at} equal {@code offered_at} and trips {@code
     * chk_deadline_after_offer} — harmless in production where the TTL is 15 s, but it makes
     * short-deadline tests impossible to write, and those are the ones that exercise FR-012. Every
     * comparison against {@code expires_at} — the sweeper's and the accept path's — uses database
     * time, so writing it from a JVM clock would reintroduce the skew that using database time
     * everywhere exists to eliminate.
     */
    public OfferId create(OrderId orderId, CourierId courierId, Duration ttl, int sequence) {
        OfferId offerId = OfferId.newId();
        jdbc.update(
                """
                INSERT INTO dispatch.offers (id, order_id, courier_id, expires_at, sequence)
                VALUES (:id, :orderId, :courierId,
                        now() + (:ttlMillis * interval '1 millisecond'), :sequence)
                """,
                new MapSqlParameterSource()
                        .addValue("id", offerId.value())
                        .addValue("orderId", orderId.value())
                        .addValue("courierId", courierId.value())
                        .addValue("ttlMillis", ttl.toMillis())
                        .addValue("sequence", sequence));
        return offerId;
    }

    /**
     * Conditional decline. Zero rows means the offer was already resolved — a no-op, not an error.
     */
    public boolean decline(OfferId offerId, CourierId callerCourierId) {
        return jdbc.update(
                        """
                        UPDATE dispatch.offers
                           SET status = 'DECLINED', responded_at = now()
                         WHERE id         = :offerId
                           AND courier_id = :courierId
                           AND status     = 'OFFERED'
                        """,
                        new MapSqlParameterSource()
                                .addValue("offerId", offerId.value())
                                .addValue("courierId", callerCourierId.value()))
                == 1;
    }

    public List<String> couriersAlreadyOffered(OrderId orderId) {
        return jdbc
                .queryForList(
                        "SELECT courier_id FROM dispatch.offers WHERE order_id = :orderId",
                        new MapSqlParameterSource("orderId", orderId.value()),
                        UUID.class)
                .stream()
                .map(UUID::toString)
                .toList();
    }

    public int nextSequence(OrderId orderId) {
        Integer max =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(sequence), 0) FROM dispatch.offers"
                                + " WHERE order_id = :orderId",
                        new MapSqlParameterSource("orderId", orderId.value()),
                        Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    public Instant deadlineOf(OfferId offerId) {
        return jdbc.queryForObject(
                        "SELECT expires_at FROM dispatch.offers WHERE id = :offerId",
                        new MapSqlParameterSource("offerId", offerId.value()),
                        java.sql.Timestamp.class)
                .toInstant();
    }
}
