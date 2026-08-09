package dev.wassal.order.domain.model;

import java.util.UUID;

/** Typed ID for the asserted merchant identity (A-03 — asserted, never verified). */
public record MerchantId(UUID value) {
    public MerchantId {
        if (value == null) throw new IllegalArgumentException("MerchantId value must not be null");
    }

    public static MerchantId of(UUID value) {
        return new MerchantId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
