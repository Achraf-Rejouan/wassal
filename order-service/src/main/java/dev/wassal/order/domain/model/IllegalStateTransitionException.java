package dev.wassal.order.domain.model;

/** Maps to {@code 409 INVALID_STATE_TRANSITION} (docs/06-api-contract.md). */
public class IllegalStateTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalStateTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal order transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
