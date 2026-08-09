package dev.wassal.dispatch.api;

import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.GeoPoint;
import dev.wassal.dispatch.infra.persistence.CourierAvailabilityService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Courier availability (FR-005). */
@RestController
@RequestMapping("/v1/couriers")
public class CourierController {

    public record AvailabilityRequest(
            boolean available,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lon) {}

    private final CourierAvailabilityService availability;

    public CourierController(CourierAvailabilityService availability) {
        this.availability = availability;
    }

    @PostMapping("/{id}/availability")
    public ResponseEntity<?> toggle(
            @RequestHeader("X-Courier-Id") UUID callerId,
            @PathVariable UUID id,
            @RequestBody AvailabilityRequest request) {

        // Record-scoped authorization: a courier may only toggle their own availability.
        if (!callerId.equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of("NOT_OWN_RECORD", "Cannot modify another courier"));
        }

        CourierId courierId = CourierId.of(id);
        var result =
                request.available()
                        ? availability.goAvailable(
                                courierId,
                                request.lat() == null || request.lon() == null
                                        ? null
                                        : new GeoPoint(request.lat(), request.lon()))
                        : availability.goOffline(courierId);

        return switch (result) {
            case CourierAvailabilityService.Result.Applied a -> ResponseEntity.ok(a);
            case CourierAvailabilityService.Result.HasActiveAssignment ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    ErrorResponse.of(
                                            "COURIER_HAS_ACTIVE_ASSIGNMENT",
                                            "Cannot go available while holding an assignment"));
            case CourierAvailabilityService.Result.PositionRequired ignored ->
                    ResponseEntity.unprocessableEntity()
                            .body(
                                    ErrorResponse.of(
                                            "POSITION_REQUIRED",
                                            "A position is required to become available"));
            case CourierAvailabilityService.Result.NotFound ignored ->
                    ResponseEntity.notFound().build();
        };
    }
}
