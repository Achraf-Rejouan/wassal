package dev.wassal.dispatch.infra.persistence;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.port.AvailabilitySet;
import dev.wassal.dispatch.infra.redis.RedisGeoCandidateFinder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Courier availability toggle (FR-005). */
@Service
public class CourierAvailabilityService {

    /** Outcome of a toggle. A guard rejection is a value, not an exception. */
    public sealed interface Result {
        record Applied(boolean available) implements Result {}

        /** Refused because the courier still holds an active assignment — guards INV-1. */
        record HasActiveAssignment() implements Result {}

        record PositionRequired() implements Result {}

        record NotFound() implements Result {}
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AvailabilitySet availability;
    private final RedisGeoCandidateFinder geoIndex;

    public CourierAvailabilityService(
            NamedParameterJdbcTemplate jdbc,
            AvailabilitySet availability,
            RedisGeoCandidateFinder geoIndex) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.geoIndex = geoIndex;
    }

    @Transactional
    public Result goAvailable(CourierId courierId, GeoPoint position) {
        if (position == null) {
            // An unlocatable courier in the availability set would be offered work and fail
            // every claim, so availability and a known position are inseparable.
            return new Result.PositionRequired();
        }

        // Guards INV-1 at the API boundary as well as at the claim. Belt and braces on purpose:
        // the claim is the real defence, but a courier who can toggle themselves available while
        // holding an assignment would be offered work the claim then has to reject, which is
        // wasted work and a confusing trace.
        Integer active =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments"
                                + " WHERE courier_id = :id AND status = 'ACTIVE'",
                        new MapSqlParameterSource("id", courierId.value()),
                        Integer.class);
        if (active != null && active > 0) {
            return new Result.HasActiveAssignment();
        }

        int updated =
                jdbc.update(
                        """
                        UPDATE dispatch.couriers
                           SET status = 'AVAILABLE',
                               last_position = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                               last_position_at = now(),
                               updated_at = now(),
                               version = version + 1
                         WHERE id = :id AND status <> 'BUSY'
                        """,
                        new MapSqlParameterSource()
                                .addValue("id", courierId.value())
                                .addValue("lat", position.lat())
                                .addValue("lon", position.lon()));
        if (updated == 0) {
            return new Result.NotFound();
        }

        // Redis after Postgres. If these two lines never ran the index would be stale and the
        // courier simply would not be offered work — a missed opportunity, never a wrong one.
        geoIndex.upsertPosition(courierId, position);
        availability.markAvailable(courierId);
        return new Result.Applied(true);
    }

    @Transactional
    public Result goOffline(CourierId courierId) {
        int updated =
                jdbc.update(
                        """
                        UPDATE dispatch.couriers
                           SET status = 'OFFLINE', updated_at = now(), version = version + 1
                         WHERE id = :id AND status = 'AVAILABLE'
                        """,
                        new MapSqlParameterSource("id", courierId.value()));
        availability.markUnavailable(courierId);
        return updated == 1 ? new Result.Applied(false) : new Result.NotFound();
    }
}
