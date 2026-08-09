package dev.wassal.order.api;

import dev.wassal.order.domain.model.IllegalStateTransitionException;
import dev.wassal.order.domain.port.IdempotencyKeyReusedException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The only place an HTTP status is chosen for an exception (docs/coding-standards.md).
 *
 * <p>Error codes are part of the API contract — the proof suites assert on them — so an unmapped
 * exception silently becoming a 500 would be a contract break no test necessarily catches. Keeping
 * the mapping in one class is what makes that auditable.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldIssue> details =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> new ErrorResponse.FieldIssue(f.getField(), f.getDefaultMessage()))
                        .toList();
        return ResponseEntity.unprocessableEntity()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED", "Request validation failed", cid(), details));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> onKeyReuse(IdempotencyKeyReusedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ErrorResponse.of(
                                "IDEMPOTENCY_KEY_REUSED",
                                "Idempotency key already used with a different payload",
                                cid()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> onIllegalTransition(IllegalStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("INVALID_STATE_TRANSITION", e.getMessage(), cid()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        String correlationId = cid();
        // Logged, never returned. The client gets an id it can quote; the detail stays here.
        log.error("Unexpected error correlationId={}", correlationId, e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("INTERNAL_ERROR", "Unexpected error", correlationId));
    }

    private static String cid() {
        String fromMdc = org.slf4j.MDC.get("correlationId");
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }
}
