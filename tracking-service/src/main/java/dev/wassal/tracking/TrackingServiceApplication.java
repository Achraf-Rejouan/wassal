package dev.wassal.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Location ingest. Exists as a separate deployable for one decisive reason: the headline chaos
 * experiment kills {@code dispatch-service}, and if ingest lived there the recovery measurement
 * could not distinguish "recovered assignment" from "recovered ingest" (ADR-0002).
 */
@SpringBootApplication
@EnableScheduling
public class TrackingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrackingServiceApplication.class, args);
    }
}
