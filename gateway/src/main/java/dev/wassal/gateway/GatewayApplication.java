package dev.wassal.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Edge: REST ingress and WebSocket termination. Holds no domain state and owns no tables — the
 * property that makes gateway instances interchangeable, which is what the Redis Pub/Sub fan-out
 * layer exists to unlock (ADR-0007).
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
