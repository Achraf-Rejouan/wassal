package dev.wassal.gateway.api;

import dev.wassal.gateway.identity.CorrelationIdFilter;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Edge proxy for order endpoints.
 *
 * <p>The gateway holds no domain state and owns no tables — it resolves asserted identity,
 * propagates correlation, and forwards. That emptiness is what makes gateway instances
 * interchangeable, which is the scaling axis the Redis Pub/Sub fan-out layer exists to unlock
 * (ADR-0007). Putting domain state here would forfeit it.
 *
 * <p>Error responses are forwarded verbatim rather than re-wrapped: the error {@code code} is part
 * of the API contract and the proof suites assert on it, so translating it at the edge would break
 * clients in a way no test necessarily catches.
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderProxyController {

    private final RestClient orderService;

    public OrderProxyController(
            RestClient.Builder builder, @Value("${wassal.upstream.order-service}") String baseUrl) {
        this.orderService = builder.baseUrl(baseUrl).build();
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody String body) {
        return forward(
                HttpMethod.POST,
                "/v1/orders",
                body,
                headers -> {
                    headers.set("X-Merchant-Id", merchantId.toString());
                    headers.set("Idempotency-Key", idempotencyKey);
                });
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> get(
            @RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID id) {
        return forward(
                HttpMethod.GET,
                "/v1/orders/" + id,
                null,
                headers -> headers.set("X-Merchant-Id", merchantId.toString()));
    }

    private ResponseEntity<String> forward(
            HttpMethod method,
            String path,
            String body,
            java.util.function.Consumer<HttpHeaders> h) {
        try {
            var spec = orderService.method(method).uri(URI.create(path));
            spec.headers(
                    headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        // Correlation must survive the hop or a trace stops at the edge.
                        headers.set(CorrelationIdFilter.HEADER, MDC.get("correlationId"));
                        h.accept(headers);
                    });
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        }
    }
}
