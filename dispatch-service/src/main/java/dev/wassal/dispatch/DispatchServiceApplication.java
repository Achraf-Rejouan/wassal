package dev.wassal.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dispatch service. In the walking skeleton it consumes {@code OrderCreated} and performs a
 * deliberately trivial assignment; the atomic claim (ADR-0004) arrives in Sprint 2.
 */
@SpringBootApplication
public class DispatchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DispatchServiceApplication.class, args);
    }
}
