package dev.wassal.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wassal.order.domain.model.CourierId;
import dev.wassal.order.domain.model.GeoPoint;
import dev.wassal.order.domain.model.IllegalStateTransitionException;
import dev.wassal.order.domain.model.MerchantId;
import dev.wassal.order.domain.model.Order;
import dev.wassal.order.domain.model.OrderId;
import dev.wassal.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exhaustive state-machine tests. No database, no Spring context — which is the point of keeping
 * domain framework-free, and why this suite runs in milliseconds.
 *
 * <p>INV-4 ("every order reaches a terminal state") is a claim about this machine, so the machine
 * is tested over its whole transition space rather than on a happy path.
 */
class OrderStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-08-09T10:00:00Z");

    private static Order anOrder() {
        return Order.create(
                OrderId.newId(),
                MerchantId.of(UUID.randomUUID()),
                new GeoPoint(36.8065, 10.1815),
                new GeoPoint(36.8189, 10.1658),
                T0);
    }

    private static Order anOrderIn(OrderStatus status) {
        Order order = anOrder();
        switch (status) {
            case PENDING -> {}
            case OFFERING -> order.transitionTo(OrderStatus.OFFERING, T0);
            case ASSIGNED -> {
                order.transitionTo(OrderStatus.OFFERING, T0);
                order.transitionTo(OrderStatus.ASSIGNED, T0);
            }
            case PICKED_UP -> {
                order.transitionTo(OrderStatus.OFFERING, T0);
                order.transitionTo(OrderStatus.ASSIGNED, T0);
                order.transitionTo(OrderStatus.PICKED_UP, T0);
            }
            case DELIVERED -> {
                order.transitionTo(OrderStatus.OFFERING, T0);
                order.transitionTo(OrderStatus.ASSIGNED, T0);
                order.transitionTo(OrderStatus.PICKED_UP, T0);
                order.transitionTo(OrderStatus.DELIVERED, T0);
            }
            case CANCELLED -> order.transitionTo(OrderStatus.CANCELLED, T0);
            case UNASSIGNABLE -> order.transitionTo(OrderStatus.UNASSIGNABLE, T0);
        }
        return order;
    }

    @Test
    @DisplayName("a new order starts PENDING with an SLA deadline 15 minutes out")
    void newOrderStartsPending() {
        Order order = anOrder();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.terminalAt()).isNull();
        assertThat(order.slaDeadline()).isEqualTo(T0.plus(Order.SLA));
        assertThat(order.offerAttempts()).isZero();
    }

    @Nested
    @DisplayName("terminal states")
    class TerminalStates {

        @ParameterizedTest
        @EnumSource(
                value = OrderStatus.class,
                names = {"DELIVERED", "CANCELLED", "UNASSIGNABLE"})
        @DisplayName("permit no further transition — INV-4's absorbing states")
        void terminalStatesAbsorb(OrderStatus terminal) {
            for (OrderStatus target : OrderStatus.values()) {
                if (target == terminal) continue;
                Order order = anOrderIn(terminal);
                assertThatThrownBy(() -> order.transitionTo(target, T0))
                        .isInstanceOf(IllegalStateTransitionException.class);
            }
        }

        @ParameterizedTest
        @EnumSource(
                value = OrderStatus.class,
                names = {"DELIVERED", "CANCELLED", "UNASSIGNABLE"})
        @DisplayName("stamp terminalAt, which is what makes the stuck-order gauge indexable")
        void terminalStampsTimestamp(OrderStatus terminal) {
            Order order = anOrderIn(terminal);

            assertThat(order.status().isTerminal()).isTrue();
            assertThat(order.terminalAt()).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(
                value = OrderStatus.class,
                names = {"PENDING", "OFFERING", "ASSIGNED", "PICKED_UP"})
        @DisplayName("non-terminal states leave terminalAt null")
        void nonTerminalLeavesTimestampNull(OrderStatus status) {
            assertThat(anOrderIn(status).terminalAt()).isNull();
        }
    }

    @Test
    @DisplayName("re-applying the state already held is an idempotent no-op, not an error")
    void samStateIsNoOp() {
        Order order = anOrderIn(OrderStatus.OFFERING);

        Order.TransitionResult result = order.transitionTo(OrderStatus.OFFERING, T0);

        // A duplicate delivery of the same command must not emit a second event (INV-5), so
        // this has to be distinguishable from an applied transition rather than merely "not
        // throwing".
        assertThat(result).isEqualTo(Order.TransitionResult.NO_OP);
        assertThat(order.status()).isEqualTo(OrderStatus.OFFERING);
    }

    @Test
    @DisplayName("ASSIGNED -> PENDING is permitted: the compensating edge INV-6 depends on")
    void assignedCanReturnToPool() {
        Order order = anOrderIn(OrderStatus.ASSIGNED);
        order.assignTo(CourierId.of(UUID.randomUUID()), T0);
        assertThat(order.assignedCourierId()).isNotNull();

        order.releaseToPool(T0);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.assignedCourierId())
                .as("release must clear the courier, or a stale assignment survives the saga")
                .isNull();
    }

    @Test
    @DisplayName("the compensating edge can be traversed repeatedly for the same order")
    void releaseIsRepeatable() {
        Order order = anOrderIn(OrderStatus.ASSIGNED);

        // The full compensating cycle is ASSIGNED -> PENDING -> OFFERING -> ASSIGNED. Neither
        // PENDING -> ASSIGNED nor ASSIGNED -> OFFERING is an edge, and that is deliberate: a
        // released order must go back through offering before it can be reassigned. Writing the
        // cycle out rather than shortcutting it is what makes INV-6's "exactly once" claim
        // meaningful across repeated releases.
        for (int i = 0; i < 3; i++) {
            order.releaseToPool(T0);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.assignedCourierId()).isNull();

            order.transitionTo(OrderStatus.OFFERING, T0);
            order.assignTo(CourierId.of(UUID.randomUUID()), T0);
            assertThat(order.status()).isEqualTo(OrderStatus.ASSIGNED);
        }

        order.releaseToPool(T0);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.assignedCourierId()).isNull();
    }

    @Test
    @DisplayName("delivery cannot skip pickup — the machine has no shortcut")
    void deliveryCannotSkipPickup() {
        Order order = anOrderIn(OrderStatus.ASSIGNED);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED, T0))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("every state can still reach a terminal state — INV-4 is not structurally blocked")
    void everyStateCanReachTerminal() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) continue;

            boolean canReachTerminal = false;
            for (OrderStatus target : OrderStatus.values()) {
                if (target.isTerminal() && status.canTransitionTo(target)) {
                    canReachTerminal = true;
                    break;
                }
            }
            assertThat(canReachTerminal)
                    .as("%s has no edge to any terminal state — INV-4 would be unprovable", status)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an order past its SLA with no terminal stamp is stuck — INV-4's failure")
    void stuckDetection() {
        Order pending = anOrderIn(OrderStatus.OFFERING);
        Order delivered = anOrderIn(OrderStatus.DELIVERED);
        Instant pastSla = T0.plus(Order.SLA).plusSeconds(1);

        assertThat(pending.isStuckAt(pastSla)).isTrue();
        assertThat(pending.isStuckAt(T0.plusSeconds(60))).isFalse();
        assertThat(delivered.isStuckAt(pastSla)).isFalse();
    }
}
