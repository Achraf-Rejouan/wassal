package dev.wassal.dispatch.api;

import java.util.UUID;

public record AssignmentResponse(UUID assignmentId, UUID orderId, UUID courierId, String status) {}
