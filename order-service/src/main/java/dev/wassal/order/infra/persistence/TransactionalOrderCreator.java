package dev.wassal.order.infra.persistence;

import dev.wassal.order.domain.port.OrderCreationUseCase;
import dev.wassal.order.domain.service.CreateOrder;
import dev.wassal.order.domain.service.OrderCreationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundary for order creation.
 *
 * <p>It lives in infra rather than on the domain service because {@code @Transactional} is a
 * framework annotation and domain is framework-free (ArchUnit enforces this). The boundary wraps
 * <em>all three</em> writes — order row, idempotency claim, outbox row — so an order without its
 * event, or an event without its order, is unreachable rather than merely unlikely (INV-5).
 *
 * <p>No network call may be made inside this boundary. A transaction held open across an HTTP or
 * Kafka call ties a database connection to a remote timeout, and the pool budget has no room for it
 * (architecture review F-5).
 */
@Component
public class TransactionalOrderCreator implements OrderCreationUseCase {

    private final OrderCreationService delegate;

    public TransactionalOrderCreator(OrderCreationService delegate) {
        this.delegate = delegate;
    }

    @Override
    @Transactional
    public OrderCreationService.Result create(CreateOrder command) {
        return delegate.create(command);
    }
}
