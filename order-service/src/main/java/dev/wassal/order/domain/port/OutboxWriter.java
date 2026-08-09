package dev.wassal.order.domain.port;

import java.util.UUID;

/**
 * Port for the transactional outbox (FR-013, INV-5).
 *
 * <p>The contract that matters: implementations must write in the <em>same transaction</em> as the
 * state change. An event for an uncommitted state change must be impossible, and a committed state
 * change without its event must be equally impossible.
 */
public interface OutboxWriter {

    void write(String aggregateType, UUID aggregateId, String eventType, Object payload);
}
