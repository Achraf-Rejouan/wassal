package dev.wassal.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The simulator. A <em>client</em> of the system, never a component of it — it reaches the services
 * only through their public API, exactly as a real courier fleet would. Direct database access
 * would entangle the ground truth with the state it is meant to verify independently (FR-020).
 *
 * <p>Single instance, deliberately. Determinism (NFR-008) requires one RNG stream; two instances
 * would produce a non-reproducible interleaving, and reproducibility is a stated requirement rather
 * than a convenience.
 */
@SpringBootApplication
public class SimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }

    @org.springframework.context.annotation.Bean
    org.springframework.data.redis.listener.RedisMessageListenerContainer offerListener(
            org.springframework.data.redis.connection.RedisConnectionFactory factory) {
        var container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    @org.springframework.context.annotation.Bean
    org.springframework.web.client.RestClient.Builder restClientBuilder() {
        return org.springframework.web.client.RestClient.builder();
    }
}
