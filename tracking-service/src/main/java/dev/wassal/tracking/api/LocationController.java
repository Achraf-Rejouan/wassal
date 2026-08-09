package dev.wassal.tracking.api;

import dev.wassal.tracking.infra.coldpath.ColdPathFlusher;
import dev.wassal.tracking.infra.fanout.PositionPublisher;
import dev.wassal.tracking.infra.hotpath.HotPositionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Location ingest (FR-010). The highest-volume endpoint in the system at 100 req/s.
 *
 * <p>No {@code Idempotency-Key} here, deliberately. {@code (courierId, recordedAt)} is a natural
 * key and the primary key already rejects duplicates, so demanding a header would add cost to the
 * hot path for a guarantee the data model provides for free.
 */
@RestController
@RequestMapping("/v1/couriers")
public class LocationController {

    public record PositionReport(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotNull Instant recordedAt,
            Double speedKmh) {}

    public record BatchReport(@NotNull @Valid List<PositionReport> positions) {}

    private final HotPositionStore hot;
    private final ColdPathFlusher cold;
    private final PositionPublisher fanout;

    public LocationController(
            HotPositionStore hot, ColdPathFlusher cold, PositionPublisher fanout) {
        this.hot = hot;
        this.cold = cold;
        this.fanout = fanout;
    }

    @PostMapping("/{id}/location")
    public ResponseEntity<Void> report(
            @RequestHeader("X-Courier-Id") UUID callerId,
            @PathVariable UUID id,
            @Valid @RequestBody PositionReport report) {

        if (!callerId.equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ingest(id.toString(), report);
        // 202: the hot path is durable enough to answer on, the cold path is asynchronous.
        return ResponseEntity.accepted().build();
    }

    /**
     * Batch form. Not needed at 300 couriers, but it is the 3,000-courier scaling remedy the
     * architecture review identified — and retrofitting it later would mean a simulator change too,
     * so the shape exists now.
     */
    @PostMapping("/{id}/locations")
    public ResponseEntity<Void> reportBatch(
            @RequestHeader("X-Courier-Id") UUID callerId,
            @PathVariable UUID id,
            @Valid @RequestBody BatchReport batch) {

        if (!callerId.equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        for (PositionReport report : batch.positions()) {
            ingest(id.toString(), report);
        }
        return ResponseEntity.accepted().build();
    }

    private void ingest(String courierId, PositionReport report) {
        long recordedAt = report.recordedAt().toEpochMilli();

        // The Lua script rejects a stale report atomically. If it loses, the cold path and the
        // fan-out are skipped too — publishing a position the hot store just rejected would
        // show a courier jumping backwards on the map.
        boolean applied = hot.apply(courierId, report.lat(), report.lon(), recordedAt);
        if (!applied) {
            return;
        }

        cold.enqueue(
                new ColdPathFlusher.Sample(
                        courierId,
                        report.lat(),
                        report.lon(),
                        report.recordedAt(),
                        report.speedKmh()));

        fanout.publish(courierId, report.lat(), report.lon(), recordedAt);
    }
}
