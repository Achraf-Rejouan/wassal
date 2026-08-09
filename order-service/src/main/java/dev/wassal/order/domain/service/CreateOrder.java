package dev.wassal.order.domain.service;

import dev.wassal.order.domain.model.GeoPoint;
import dev.wassal.order.domain.model.MerchantId;

/** Command. Named VerbAggregate per docs/coding-standards.md. */
public record CreateOrder(
        MerchantId merchantId, GeoPoint pickup, GeoPoint dropoff, String idempotencyKey) {}
