package dev.wassal.contracts.assignment;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by dispatch-service when a claim succeeds. In the walking skeleton the claim is
 * trivial (first available courier); the atomic claim arrives in Sprint 2.
 */
public record AssignmentCreated(
        UUID assignmentId, UUID orderId, UUID courierId, Instant assignedAt) {}
