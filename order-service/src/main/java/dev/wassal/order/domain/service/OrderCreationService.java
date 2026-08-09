package dev.wassal.order.domain.service;

import dev.wassal.contracts.order.OrderCreated;
import dev.wassal.order.domain.model.Order;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.port.IdempotencyStore;
import dev.wassal.order.domain.port.OrderRepository;
import dev.wassal.order.domain.port.OutboxWriter;
import java.time.Clock;
import java.time.Instant;

/**
 * Creates an order, idempotently, with its domain event (FR-001, FR-013, FR-014).
 *
 * <p>Three things happen in one transaction — the order row, the idempotency claim, and the outbox
 * row — and the transaction boundary is applied by the caller in infra, not here, because
 * {@code @Transactional} is a framework annotation and this class is domain.
 *
 * <p>The idempotency claim uses the unique constraint as the concurrency mechanism rather than a
 * lookup-then-insert. Two simultaneous requests with the same key both attempt the insert; one wins
 * and the loser reads the winner's result. Checking first would be a check-then-act with a window
 * between the check and the write.
 */
public class OrderCreationService {

    /** Outcome of a creation attempt — distinguishes a fresh order from a replayed one. */
    public record Result(OrderId orderId, boolean created) {}

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public OrderCreationService(
            OrderRepository orders,
            OutboxWriter outbox,
            IdempotencyStore idempotency,
            Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public Result create(CreateOrder command) {
        Instant now = clock.instant();
        OrderId orderId = OrderId.newId();

        Order order =
                Order.create(
                        orderId, command.merchantId(), command.pickup(), command.dropoff(), now);

        orders.insert(order);

        // Claim the key AFTER inserting the order so the FK is satisfiable. If the claim
        // loses, the whole transaction rolls back — including the order just inserted — and
        // the winner's id is returned instead. That rollback is why no orphan order can exist.
        var claim =
                idempotency.claim(command.merchantId(), command.idempotencyKey(), command, orderId);

        if (!claim.won()) {
            return new Result(claim.existingOrderId(), false);
        }

        outbox.write(
                "Order",
                orderId.value(),
                "OrderCreated",
                new OrderCreated(
                        orderId.value(),
                        command.merchantId().value(),
                        order.pickup().lat(),
                        order.pickup().lon(),
                        order.dropoff().lat(),
                        order.dropoff().lon(),
                        order.createdAt(),
                        order.slaDeadline()));

        return new Result(orderId, true);
    }
}
