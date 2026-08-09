package dev.wassal.dispatch.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one error envelope. {@code code} is a stable part of the contract; the suites assert on it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String correlationId) {
    public static ErrorResponse of(String code, String message) {
        String cid = org.slf4j.MDC.get("correlationId");
        return new ErrorResponse(
                code, message, cid != null ? cid : java.util.UUID.randomUUID().toString());
    }
}
