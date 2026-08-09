package dev.wassal.order.domain.port;

import dev.wassal.order.domain.service.CreateOrder;
import dev.wassal.order.domain.service.OrderCreationService;

/**
 * Inbound port for order creation.
 *
 * <p>Exists because the transaction boundary lives in infra (it needs {@code @Transactional}, a
 * framework annotation domain must not carry) while the API layer must not depend on infra. The
 * controller depends on this interface; infra provides the transactional implementation.
 *
 * <p>Introduced after {@code LayeringArchTest.apiDoesNotReachIntoInfra} caught the controller
 * importing the transactional wrapper directly — exactly the kind of violation that is invisible in
 * any single file and creeps in one import at a time.
 */
public interface OrderCreationUseCase {

    OrderCreationService.Result create(CreateOrder command);
}
