package dev.wassal.order.domain.model;

import java.util.UUID;

/** Typed ID — see {@link OrderId} for why bare UUIDs are not used on the claim path. */
public record CourierId(UUID value) {
    public CourierId {
        if (value == null) throw new IllegalArgumentException("CourierId value must not be null");
    }

    public static CourierId of(UUID value) {
        return new CourierId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
