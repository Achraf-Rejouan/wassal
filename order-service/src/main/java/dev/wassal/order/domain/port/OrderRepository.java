package dev.wassal.order.domain.port;

import dev.wassal.order.domain.model.Order;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.model.OrderStatus;
import java.util.Optional;

/**
 * Port. The implementation lives in infra and is the only thing that knows about JPA.
 *
 * <p>{@link #applyTransition} exists rather than a generic {@code save} because the transition must
 * be a <em>conditional</em> update whose affected-row count decides the outcome. A save that read,
 * mutated and wrote would be a check-then-act — the central defect class this project exists to
 * disprove.
 */
public interface OrderRepository {

    void insert(Order order);

    Optional<Order> findById(OrderId id);

    /**
     * Conditional transition. Returns {@code true} only if exactly one row moved from {@code
     * expected} to {@code target}. A {@code false} return means someone else won the race and the
     * caller must not retry blindly.
     */
    boolean applyTransition(OrderId id, OrderStatus expected, OrderStatus target);
}
