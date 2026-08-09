package dev.wassal.order.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle states and the explicit transition table (FR-002).
 *
 * <p>INV-4 — "every order reaches a terminal state" — is unprovable without an explicit machine,
 * which is why the table below is data rather than a scatter of {@code if} statements. The validity
 * check is exhaustive and testable in isolation, with no database involved.
 *
 * <p>{@code ASSIGNED -> PENDING} is the only backward edge. It is the compensating transition from
 * cancellation, and it is what makes INV-6 non-trivial: the courier must be released exactly once
 * on that edge, and the edge can be traversed repeatedly for the same order.
 */
public enum OrderStatus {
    PENDING,
    OFFERING,
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    CANCELLED,
    UNASSIGNABLE;

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(DELIVERED, CANCELLED, UNASSIGNABLE);

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
            Map.of(
                    PENDING, EnumSet.of(OFFERING, UNASSIGNABLE, CANCELLED),
                    OFFERING, EnumSet.of(PENDING, ASSIGNED, UNASSIGNABLE, CANCELLED),
                    ASSIGNED, EnumSet.of(PENDING, PICKED_UP, CANCELLED),
                    PICKED_UP, EnumSet.of(DELIVERED, CANCELLED),
                    DELIVERED, EnumSet.noneOf(OrderStatus.class),
                    CANCELLED, EnumSet.noneOf(OrderStatus.class),
                    UNASSIGNABLE, EnumSet.noneOf(OrderStatus.class));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether this transition is permitted. A transition to the state already held is treated as a
     * valid idempotent no-op by the caller (FR-002) rather than as a machine edge, so it is
     * deliberately <em>not</em> in the table.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }
}
