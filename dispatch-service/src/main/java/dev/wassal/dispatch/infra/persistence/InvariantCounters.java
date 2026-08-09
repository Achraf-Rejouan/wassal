package dev.wassal.dispatch.infra.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runtime observability for the invariants (FR-015).
 *
 * <p>A test proves an invariant held in the lab; a counter proves it is holding now. The plan
 * requires both, because either alone is insufficient — a violation that happens and is not counted
 * makes every published number a lie, which is the worst outcome in this project's threat model.
 *
 * <p>Every counter here is registered eagerly at startup rather than lazily on first increment, so
 * a dashboard shows a real zero instead of a missing series. "No data" and "zero violations" look
 * identical on a graph and mean opposite things.
 */
@Component
public class InvariantCounters {

    private static final Logger log = LoggerFactory.getLogger(InvariantCounters.class);

    /** The invariants, with the database constraint that enforces each where one exists. */
    public enum Invariant {
        INV_1("uq_active_assignment_per_courier"),
        INV_2("uq_active_assignment_per_order"),
        INV_3("uq_assignment_per_offer"),
        INV_4(null),
        INV_5(null),
        INV_6(null);

        private final String constraint;

        Invariant(String constraint) {
            this.constraint = constraint;
        }

        static Invariant forConstraint(String message) {
            if (message == null) return null;
            for (Invariant i : values()) {
                if (i.constraint != null && message.contains(i.constraint)) return i;
            }
            return null;
        }
    }

    private final Map<Invariant, Counter> violations = new EnumMap<>(Invariant.class);
    private final Counter assignments;
    private final Counter failedClaims;
    private final Counter expiredAccepts;

    public InvariantCounters(MeterRegistry registry) {
        for (Invariant invariant : Invariant.values()) {
            violations.put(
                    invariant,
                    Counter.builder("wassal_invariant_violation_total")
                            .tag("invariant", invariant.name().replace('_', '-'))
                            .description("Runtime invariant violations — must stay at zero")
                            .register(registry));
        }
        assignments = Counter.builder("wassal_assignments_created_total").register(registry);
        failedClaims =
                Counter.builder("wassal_claim_failed_total")
                        .description(
                                "Claims that lost a race. Expected and healthy under contention"
                                        + " — a stress run where this stays zero means the race never"
                                        + " happened and the test proved nothing")
                        .register(registry);
        expiredAccepts = Counter.builder("wassal_accept_after_deadline_total").register(registry);
    }

    public void recordAssignment() {
        assignments.increment();
    }

    public void recordFailedClaim() {
        failedClaims.increment();
    }

    public void recordExpiredAccept() {
        expiredAccepts.increment();
    }

    /**
     * A unique-constraint rejection on the assignment insert means the database refused what the
     * application logic permitted. The invariant <em>held</em> — that is what the constraint is for
     * — but reaching this point is a defect, so it is logged at ERROR with the invariant named
     * rather than absorbed as an ordinary lost race.
     */
    public void recordConstraintRejection(Exception e) {
        String message = rootMessage(e);
        Invariant invariant = Invariant.forConstraint(message);
        if (invariant != null) {
            violations.get(invariant).increment();
            log.error(
                    "{} violation attempt refused by the database — the constraint held, but the"
                            + " claim logic should never have got here: {}",
                    invariant.name().replace('_', '-'),
                    message);
        } else {
            log.error("Unexpected constraint rejection on assignment insert: {}", message);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** Test-only hook: deliberately injects a violation so the counter is observed non-zero. */
    public void injectViolationForTest(Invariant invariant) {
        violations.get(invariant).increment();
    }

    public double violationCount(Invariant invariant) {
        return violations.get(invariant).count();
    }

    public double failedClaimCount() {
        return failedClaims.count();
    }
}
