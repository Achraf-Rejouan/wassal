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

        // Claim the idempotency key FIRST. The unique constraint arbitrates concurrent
        // duplicates, and claiming before any other write is what makes losing free: the loser
        // returns the winner's id having inserted nothing.
        //
        // The first version of this method inserted the order first and returned early when the
        // claim lost — which committed the order anyway, producing one order per concurrent
        // request. The transaction only rolls back if something throws, and returning normally
        // is not throwing. Order matters here, not just atomicity.
        var claim =
                idempotency.claim(command.merchantId(), command.idempotencyKey(), command, orderId);

        if (!claim.won()) {
            return new Result(claim.existingOrderId(), false);
        }

        Order order =
                Order.create(
                        orderId, command.merchantId(), command.pickup(), command.dropoff(), now);

        orders.insert(order);

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
