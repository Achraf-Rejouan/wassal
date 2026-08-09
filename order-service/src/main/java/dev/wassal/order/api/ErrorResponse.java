package dev.wassal.order.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The single error envelope (docs/06-api-contract.md).
 *
 * <p>{@code code} is a stable machine-readable identifier and part of the contract — the simulator
 * and the proof suites assert on it. {@code message} is prose and may change freely. Stack traces
 * are never returned; they are logged against the correlation id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code, String message, String correlationId, List<FieldIssue> details) {

    public record FieldIssue(String field, String issue) {}

    public static ErrorResponse of(String code, String message, String correlationId) {
        return new ErrorResponse(code, message, correlationId, null);
    }
}
