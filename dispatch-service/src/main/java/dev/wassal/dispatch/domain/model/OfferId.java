package dev.wassal.dispatch.domain.model;

import java.util.UUID;

/**
 * Typed ID. {@code claim(UUID, UUID, UUID)} compiles cleanly with two arguments swapped and
 * produces a wrong assignment — and INV-1 and INV-2 are this project's headline claims, so making
 * their arguments unswappable is cheap insurance.
 */
public record OfferId(UUID value) {
    public OfferId {
        if (value == null) throw new IllegalArgumentException("OfferId must not be null");
    }

    public static OfferId of(UUID value) {
        return new OfferId(value);
    }

    public static OfferId newId() {
        return new OfferId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
