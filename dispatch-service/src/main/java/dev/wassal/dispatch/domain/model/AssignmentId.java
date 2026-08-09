package dev.wassal.dispatch.domain.model;

import java.util.UUID;

/**
 * Typed ID. {@code claim(UUID, UUID, UUID)} compiles cleanly with two arguments swapped and
 * produces a wrong assignment — and INV-1 and INV-2 are this project's headline claims, so making
 * their arguments unswappable is cheap insurance.
 */
public record AssignmentId(UUID value) {
    public AssignmentId {
        if (value == null) throw new IllegalArgumentException("AssignmentId must not be null");
    }

    public static AssignmentId of(UUID value) {
        return new AssignmentId(value);
    }

    public static AssignmentId newId() {
        return new AssignmentId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
