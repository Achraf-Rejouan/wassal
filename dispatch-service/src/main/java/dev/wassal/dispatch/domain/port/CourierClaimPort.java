package dev.wassal.dispatch.domain.port;

import dev.wassal.dispatch.domain.model.ClaimResult;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;

/**
 * The atomic claim (ADR-0004, FR-008) — INV-1, INV-2 and INV-3 all rest on its implementation.
 *
 * <p>The contract implementations must honour, stated here because it is easy to satisfy the
 * signature while breaking the guarantee:
 *
 * <ul>
 *   <li>One transaction. No read-then-write anywhere on this path.
 *   <li>Every precondition is a predicate in a conditional {@code UPDATE}, and the affected-row
 *       count is the decision — including the caller's identity and the deadline.
 *   <li>Fail closed: if a precondition cannot be verified, no assignment is created.
 *   <li>Losing returns a {@link ClaimResult}; it does not throw.
 * </ul>
 */
public interface CourierClaimPort {

    ClaimResult acceptOffer(OfferId offerId, CourierId callerCourierId);
}
