package dev.wassal.dispatch.domain.port;

import dev.wassal.dispatch.domain.model.CourierId;

/**
 * The set of couriers currently AVAILABLE, owned by dispatch-service (architecture review F-3).
 *
 * <p>Ownership is split deliberately: tracking-service owns <em>where</em> every courier is,
 * dispatch owns <em>who is free</em>. The first draft had one structure holding "available couriers
 * with positions", which would have required tracking-service to know availability — state it is
 * forbidden from reading.
 *
 * <p>This is a cache. It may disagree with Postgres, and that is tolerated: the claim is
 * authoritative, so a stale member costs one failed attempt and a stale absence costs one missed
 * candidate. Neither can produce a wrong assignment.
 */
public interface AvailabilitySet {

    void markAvailable(CourierId courierId);

    void markUnavailable(CourierId courierId);

    boolean contains(CourierId courierId);

    long size();

    /** Publishes the order -> courier mapping the gateway resolves on WebSocket subscribe. */
    void publishAssignment(String orderId, String courierId);
}
