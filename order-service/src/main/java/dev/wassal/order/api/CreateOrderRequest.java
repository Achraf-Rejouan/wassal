package dev.wassal.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Wire shape. Never reaches a repository; never returned from a controller. */
public record CreateOrderRequest(
        @NotNull @Valid Coordinates pickup,
        @NotNull @Valid Coordinates dropoff,
        @Size(max = 200) String pickupAddress,
        @Size(max = 200) String dropoffAddress) {

    public record Coordinates(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon) {}
}
