package dev.wassal.dispatch.domain.model;

import java.util.UUID;

/**
 * Typed ID. {@code claim(UUID, UUID, UUID)} compiles cleanly with two arguments swapped and
 * produces a wrong assignment — and INV-1 and INV-2 are this project's headline claims, so making
 * their arguments unswappable is cheap insurance.
 */
public record OrderId(UUID value) {
    public OrderId {
        if (value == null) throw new IllegalArgumentException("OrderId must not be null");
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    public static OrderId newId() {
        return new OrderId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
