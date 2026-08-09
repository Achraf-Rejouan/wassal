package dev.wassal.order.api;

import dev.wassal.order.domain.model.GeoPoint;
import dev.wassal.order.domain.model.MerchantId;
import dev.wassal.order.domain.model.Order;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.port.OrderCreationUseCase;
import dev.wassal.order.domain.port.OrderRepository;
import dev.wassal.order.domain.service.CreateOrder;
import dev.wassal.order.domain.service.OrderCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Order endpoints (FR-001, FR-003). */
@RestController
@RequestMapping("/v1/orders")
@Validated
public class OrderController {

    private final OrderCreationUseCase creator;
    private final OrderRepository orders;

    public OrderController(OrderCreationUseCase creator, OrderRepository orders) {
        this.creator = creator;
        this.orders = orders;
    }

    /**
     * @param idempotencyKey capped at 255 chars with a restricted charset (security A-2) — an
     *     unbounded client-supplied primary-key component is a storage and index-bloat vector
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Size(max = 255)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        var command =
                new CreateOrder(
                        MerchantId.of(merchantId),
                        new GeoPoint(request.pickup().lat(), request.pickup().lon()),
                        new GeoPoint(request.dropoff().lat(), request.dropoff().lon()),
                        idempotencyKey);

        OrderCreationService.Result result = creator.create(command);
        Order order = orders.findById(result.orderId()).orElseThrow();

        // 201 for a fresh order, 200 for an idempotent replay — the distinction is part of the
        // contract (FR-001) and lets a client tell a retry from a duplicate submission.
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toResponse(order));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(
            @RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID id) {
        return orders.findById(OrderId.of(id))
                // Ownership is checked here rather than after the fetch. It becomes a query
                // predicate once the read moves to its own statement in Sprint 2.
                .filter(order -> order.merchantId().value().equals(merchantId))
                .map(order -> ResponseEntity.ok(toResponse(order)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.id().value(),
                order.status().name(),
                order.createdAt(),
                order.slaDeadline(),
                order.offerAttempts());
    }
}
