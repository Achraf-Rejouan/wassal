package dev.wassal.order.domain.port;

/** Maps to {@code 409 IDEMPOTENCY_KEY_REUSED} — same key, different payload. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency key reused with a different payload: " + key);
    }
}
