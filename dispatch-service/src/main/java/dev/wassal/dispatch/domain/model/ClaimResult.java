package dev.wassal.dispatch.domain.model;

/**
 * The outcome of an acceptance attempt.
 *
 * <p><strong>A lost claim is a return value, not an exception.</strong> Under the stress profile
 * losing is the <em>majority</em> outcome — thousands of couriers race for tens of couriers-worth
 * of work — so modelling it as exceptional would be both slow and semantically wrong. Callers must
 * handle every variant; the sealed hierarchy makes the compiler check that.
 */
public sealed interface ClaimResult {

    /** The claim succeeded and exactly one assignment now exists. */
    record Assigned(AssignmentId assignmentId, OrderId orderId, CourierId courierId)
            implements ClaimResult {}

    /**
     * A prior identical accept already produced this assignment (INV-3). Returned rather than
     * re-claiming, so a client retrying over a flaky network gets the same answer.
     */
    record AlreadyAssigned(AssignmentId assignmentId, OrderId orderId, CourierId courierId)
            implements ClaimResult {}

    /**
     * The deadline had passed. Distinct from {@link CourierUnavailable} because it is a loss
     * against <em>time</em>, not against another actor — the client should surface it differently,
     * and the proof suite asserts on the distinction (FR-012).
     */
    record OfferExpired(OfferId offerId) implements ClaimResult {}

    /** Another accept won the courier first. The normal outcome of contention. */
    record CourierUnavailable(OfferId offerId, CourierId courierId) implements ClaimResult {}

    /** The order was claimed by someone else (INV-2). */
    record OrderAlreadyAssigned(OrderId orderId) implements ClaimResult {}

    /** The offer is not addressed to this courier, or does not exist. */
    record NotOfferRecipient(OfferId offerId) implements ClaimResult {}
}
