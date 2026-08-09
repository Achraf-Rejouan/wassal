package dev.wassal.dispatch.domain.port;

import dev.wassal.dispatch.domain.model.Candidate;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.domain.model.OrderId;
import java.util.List;

/**
 * Finds nearby available couriers (FR-006).
 *
 * <p>The interface deliberately hides which store answers. It is served by Redis today (ADR-0003)
 * because the workload is ~40:1 write-to-read and PostGIS would fail NFR-003 arithmetically — but
 * nothing above this line depends on that.
 */
public interface CandidateFinder {

    /**
     * @param excludeAlreadyOffered couriers already offered this order, which must not be offered
     *     it again — otherwise a declining courier is asked repeatedly and the order never exhausts
     *     its candidate list
     */
    List<Candidate> findNearestAvailable(
            OrderId orderId,
            GeoPoint around,
            double radiusMetres,
            int limit,
            List<String> excludeAlreadyOffered);
}
