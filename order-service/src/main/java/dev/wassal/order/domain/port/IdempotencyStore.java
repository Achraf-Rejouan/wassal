package dev.wassal.order.domain.port;

import dev.wassal.order.domain.model.MerchantId;
import dev.wassal.order.domain.model.OrderId;

/**
 * Port for idempotent command claiming (FR-014).
 *
 * <p>The contract is deliberately a <em>claim</em> rather than a lookup: implementations must
 * resolve concurrent duplicates with a unique constraint, not by checking first. Two simultaneous
 * requests with the same key must produce exactly one order.
 */
public interface IdempotencyStore {

    /**
     * @param won true if this caller created the record; false if a prior request already claimed
     *     the key, in which case {@code existingOrderId} is that request's result
     */
    record Claim(boolean won, OrderId existingOrderId) {
        public static Claim claimed() {
            return new Claim(true, null);
        }

        public static Claim alreadyClaimedBy(OrderId existing) {
            return new Claim(false, existing);
        }
    }

    /**
     * @throws IdempotencyKeyReusedException if the key was used with a different payload — which is
     *     a client bug, not a race, and must not silently return the old result
     */
    Claim claim(MerchantId merchantId, String key, Object request, OrderId orderId);
}
