package dev.wassal.dispatch.api;

import dev.wassal.dispatch.domain.model.ClaimResult;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.port.CourierClaimPort;
import dev.wassal.dispatch.infra.persistence.AtomicClaimExecutor;
import dev.wassal.dispatch.infra.persistence.OfferRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offer accept and decline (FR-008, FR-012).
 *
 * <p>The whole controller is a mapping from {@link ClaimResult} to status codes. Two of those
 * mappings carry design meaning rather than convention:
 *
 * <ul>
 *   <li><strong>409 {@code COURIER_UNAVAILABLE} is the system working.</strong> Under the stress
 *       profile it is the majority response. It carries no {@code Retry-After} and the client must
 *       treat it as final — a client that retries into a claim it already lost is the fastest route
 *       to violating INV-1.
 *   <li><strong>410 is not 409.</strong> 409 means you lost against another actor; 410 means you
 *       lost against time. Separating them lets the proof suite assert on the accept-vs-expire race
 *       directly instead of inferring it.
 * </ul>
 */
@RestController
@RequestMapping("/v1/offers")
public class OfferController {

    private final CourierClaimPort claim;
    private final OfferRepository offers;

    public OfferController(CourierClaimPort claim, OfferRepository offers) {
        this.claim = claim;
        this.offers = offers;
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(
            @RequestHeader("X-Courier-Id") UUID courierId, @PathVariable UUID id) {

        ClaimResult result;
        try {
            result = claim.acceptOffer(OfferId.of(id), CourierId.of(courierId));
        } catch (AtomicClaimExecutor.ClaimLostException e) {
            // The transaction rolled back so nothing partial survives; the outcome rode out on
            // the exception and is reported as an ordinary result.
            result = e.result();
        }

        return switch (result) {
            case ClaimResult.Assigned a ->
                    ResponseEntity.status(HttpStatus.CREATED).body(toResponse(a));
            // A replay returns 200 with the ORIGINAL assignment (INV-3). Not 201, because
            // nothing was created; not an error, because the caller's intent was satisfied.
            case ClaimResult.AlreadyAssigned a -> ResponseEntity.ok(toResponse(a));
            case ClaimResult.OfferExpired ignored ->
                    ResponseEntity.status(HttpStatus.GONE)
                            .body(ErrorResponse.of("OFFER_EXPIRED", "Offer deadline has passed"));
            case ClaimResult.CourierUnavailable ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    ErrorResponse.of(
                                            "COURIER_UNAVAILABLE",
                                            "Courier is no longer available"));
            case ClaimResult.OrderAlreadyAssigned ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    ErrorResponse.of(
                                            "ORDER_ALREADY_ASSIGNED",
                                            "Order already has an active assignment"));
            case ClaimResult.NotOfferRecipient ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(
                                    ErrorResponse.of(
                                            "NOT_OFFER_RECIPIENT",
                                            "Offer is not addressed to this courier"));
        };
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<?> decline(
            @RequestHeader("X-Courier-Id") UUID courierId, @PathVariable UUID id) {
        // Naturally idempotent: declining an already-resolved offer is a no-op, not an error.
        offers.decline(OfferId.of(id), CourierId.of(courierId));
        return ResponseEntity.noContent().build();
    }

    private static AssignmentResponse toResponse(ClaimResult.Assigned a) {
        return new AssignmentResponse(
                a.assignmentId().value(), a.orderId().value(), a.courierId().value(), "ACTIVE");
    }

    private static AssignmentResponse toResponse(ClaimResult.AlreadyAssigned a) {
        return new AssignmentResponse(
                a.assignmentId().value(), a.orderId().value(), a.courierId().value(), "ACTIVE");
    }
}
