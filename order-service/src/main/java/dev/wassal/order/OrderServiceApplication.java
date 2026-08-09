package dev.wassal.order;

import dev.wassal.order.domain.port.IdempotencyStore;
import dev.wassal.order.domain.port.OrderRepository;
import dev.wassal.order.domain.port.OutboxWriter;
import dev.wassal.order.domain.service.OrderCreationService;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /**
     * The domain service is constructed here rather than annotated, because it lives in domain and
     * domain carries no Spring annotations. Wiring is an infrastructure concern.
     */
    @Bean
    OrderCreationService orderCreationService(
            OrderRepository orders,
            OutboxWriter outbox,
            IdempotencyStore idempotency,
            Clock clock) {
        return new OrderCreationService(orders, outbox, idempotency, clock);
    }

    /** Injected rather than static, so time is controllable in tests (NFR-008 determinism). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
