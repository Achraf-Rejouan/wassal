package dev.wassal.order.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * The order aggregate. Pure domain — no JPA, no Spring, no Jackson. That constraint is what lets
 * the state machine be tested exhaustively without a database, and exhaustive state-machine testing
 * is what INV-4 rests on (ArchUnit enforces it).
 */
public final class Order {

    /** SLA window used to define INV-4's "beyond its SLA". */
    public static final Duration SLA = Duration.ofMinutes(15);

    private final OrderId id;
    private final MerchantId merchantId;
    private final GeoPoint pickup;
    private final GeoPoint dropoff;
    private final Instant createdAt;
    private final Instant slaDeadline;

    private OrderStatus status;
    private CourierId assignedCourierId;
    private int offerAttempts;
    private Instant terminalAt;
    private long version;

    private Order(
            OrderId id,
            MerchantId merchantId,
            GeoPoint pickup,
            GeoPoint dropoff,
            Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.createdAt = createdAt;
        this.slaDeadline = createdAt.plus(SLA);
        this.status = OrderStatus.PENDING;
        this.offerAttempts = 0;
        this.version = 0;
    }

    public static Order create(
            OrderId id,
            MerchantId merchantId,
            GeoPoint pickup,
            GeoPoint dropoff,
            Instant createdAt) {
        return new Order(id, merchantId, pickup, dropoff, createdAt);
    }

    /** Rehydration from persistence. Bypasses transition rules by design. */
    public static Order rehydrate(
            OrderId id,
            MerchantId merchantId,
            GeoPoint pickup,
            GeoPoint dropoff,
            Instant createdAt,
            OrderStatus status,
            CourierId assignedCourierId,
            int offerAttempts,
            Instant terminalAt,
            long version) {
        Order order = new Order(id, merchantId, pickup, dropoff, createdAt);
        order.status = status;
        order.assignedCourierId = assignedCourierId;
        order.offerAttempts = offerAttempts;
        order.terminalAt = terminalAt;
        order.version = version;
        return order;
    }

    /**
     * Applies a state transition, or rejects it.
     *
     * <p>Returns {@link TransitionResult#NO_OP} when the order already holds the target state — a
     * duplicate delivery of the same command must not emit a second event (FR-002, INV-5). Throws
     * only for transitions the machine forbids, which map to {@code 409 INVALID_STATE_TRANSITION}.
     *
     * <p>This method does <em>not</em> guard against concurrent transitions. That is deliberate:
     * concurrency is settled by the conditional {@code UPDATE ... WHERE status = :expected} in the
     * persistence layer, whose affected-row count is the decision. Checking here as well would be a
     * check-then-act and would read as if it were doing the work.
     */
    public TransitionResult transitionTo(OrderStatus target, Instant at) {
        if (status == target) {
            return TransitionResult.NO_OP;
        }
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
        if (target.isTerminal()) {
            this.terminalAt = at;
        }
        return TransitionResult.APPLIED;
    }

    public void assignTo(CourierId courierId, Instant at) {
        transitionTo(OrderStatus.ASSIGNED, at);
        this.assignedCourierId = courierId;
    }

    /** Compensating release — the backward edge. Clears the assignment (INV-6). */
    public void releaseToPool(Instant at) {
        transitionTo(OrderStatus.PENDING, at);
        this.assignedCourierId = null;
    }

    public void recordOfferAttempt() {
        this.offerAttempts++;
    }

    /**
     * INV-4's failure condition: non-terminal past its SLA. Kept on the aggregate so the rule has
     * one definition; the runtime gauge uses the equivalent indexed SQL predicate.
     */
    public boolean isStuckAt(Instant now) {
        return terminalAt == null && now.isAfter(slaDeadline);
    }

    public enum TransitionResult {
        APPLIED,
        NO_OP
    }

    public OrderId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public GeoPoint pickup() {
        return pickup;
    }

    public GeoPoint dropoff() {
        return dropoff;
    }

    public OrderStatus status() {
        return status;
    }

    public CourierId assignedCourierId() {
        return assignedCourierId;
    }

    public int offerAttempts() {
        return offerAttempts;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant slaDeadline() {
        return slaDeadline;
    }

    public Instant terminalAt() {
        return terminalAt;
    }

    public long version() {
        return version;
    }
}
