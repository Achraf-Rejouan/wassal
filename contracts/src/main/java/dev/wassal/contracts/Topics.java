package dev.wassal.contracts;

/**
 * Kafka topic names. Two topics only — each carries a genuine async boundary and is justified
 * by a named failure it isolates (docs/04-architecture.md). A topic whose only consumer is its
 * own producer is decoration.
 */
public final class Topics {

    /**
     * Isolates order acceptance from dispatch availability. If dispatch-service is down, orders
     * are still accepted and dispatched on recovery — the failure the chaos suite induces.
     */
    public static final String ORDER_LIFECYCLE = "order.lifecycle";

    /** Isolates assignment decisions from downstream consumers and feeds reconciliation. */
    public static final String ASSIGNMENT_LIFECYCLE = "assignment.lifecycle";

    private Topics() {}
}
