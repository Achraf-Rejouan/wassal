package dev.wassal.dispatch.domain.model;

import java.util.UUID;

/**
 * Typed ID. {@code claim(UUID, UUID, UUID)} compiles cleanly with two arguments swapped and
 * produces a wrong assignment — and INV-1 and INV-2 are this project's headline claims, so making
 * their arguments unswappable is cheap insurance.
 */
public record CourierId(UUID value) {
    public CourierId {
        if (value == null) throw new IllegalArgumentException("CourierId must not be null");
    }

    public static CourierId of(UUID value) {
        return new CourierId(value);
    }

    public static CourierId newId() {
        return new CourierId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
