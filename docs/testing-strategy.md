# Testing Strategy

**Date:** 2026-08-09 · **Status:** implemented through Sprint 5c

> The delivery plan carried a testing *table*. This document is the strategy behind it —
> written after four sprints of building, so it records what the suites actually do and what
> they are actually worth, not what was hoped for at planning time.

---

## The premise

Most test strategies optimise for coverage. This one optimises for a different question:
**would this suite notice if the system's central claim became false?**

That reframing matters here because Wassal's deliverable is *evidence*, not features. A suite
that runs green while INV-1 is quietly violated has not merely failed to catch a bug — it has
manufactured a false claim, which is worse than no claim at all. Every decision below follows
from that.

Three rules fall out of it, and they are the whole strategy:

1. **A test that cannot fail is not a test.** Applied literally: if a concurrency test's race
   never occurred, the test must *fail*, not pass quietly.
2. **Test the mechanism that actually enforces the property.** INV-1 is enforced by a partial
   unique index. So the test must hit a real Postgres, because a mock cannot refuse a write.
3. **Assert on converged state, never on transient state.** This is what stops chaos tests
   being flaky — and flaky tests get disabled, which silently deletes the proof.

---

## The layers

| Layer | What it proves | Runs against | Where | Gate |
|---|---|---|---|---|
| **Unit** | State machines, invariant logic, value objects | Nothing — pure JVM | `*/src/test` | Every push |
| **Architecture** | Layering, module boundaries, no infra mocking | Compiled bytecode | `*ArchTest` | Every push |
| **Integration** | Behaviour against real Postgres, Redis, Redpanda | Testcontainers | `*IT` | Every push |
| **Concurrency** | INV-1, INV-2, INV-3 under manufactured contention | Testcontainers | `AtomicClaimIT` | Every push |
| **Durability** | FR-011, FR-012, INV-4, INV-6 across process death | Testcontainers | `DurabilityIT` | Every push |
| **Fault injection** | NFR-012 fail-closed under partition and latency | Toxiproxy | `proof/chaos` | Separate workflow |
| **Chaos** | NFR-005 recovery, measured | The real Compose stack | `scripts/chaos-run.sh` | Manual / pre-release |
| **Comparison** | ADR-0004's rejected alternative, benchmarked | Testcontainers | `RedisLockComparisonTest` | Every push |

Note what is **absent**: no mocking framework appears anywhere below the unit layer, there are
no controller slice tests, and there is no coverage gate outside `domain`. Each omission is
deliberate and explained below.

---

## Rule 1 — A test that cannot fail is not a test

The single most dangerous test in a project like this is the concurrency test that passes
because the race never happened. It is indistinguishable from a real pass in every report, in
CI, and in a README.

So the contended suites assert that contention **occurred**:

```java
assertThat(lost)
    .as("contention must actually have occurred — zero lost claims means the race"
        + " never happened and this test proved nothing")
    .isGreaterThan(0);
```

The same principle, applied elsewhere:

| Test | The assertion that makes it real |
|---|---|
| `AtomicClaimIT.inv1…` | `failedClaims > 0` — the race is proven to have fired |
| `AtomicClaimIT.countersCanFire` | Every invariant counter is deliberately incremented once. **A counter never seen non-zero is untested code** |
| `FailClosedUnderPartitionTest.constraintHolds…` | Inserts *directly* into the table, bypassing all application logic. If the invariant lived in code, this would succeed |
| `reconciliation` (stretch) | A deliberately corrupted row must be **detected** — the job must be able to fail |

**How contention is manufactured.** At the design target of 50 orders/min the double-assignment
race occurs approximately never. `AtomicClaimIT` creates 5,000 live offers across 50 couriers
and releases every accept simultaneously on a `CountDownLatch`. Without the latch the thread
pool staggers the requests and they quietly serialise — which is exactly how a concurrency
suite ends up proving nothing while looking thorough.

---

## Rule 2 — Test the mechanism, not a model of it

**No infrastructure is ever mocked.** This is an ArchUnit rule, not a preference (NFR-009).

The reasoning is concrete rather than dogmatic. INV-1 is enforced by:

```sql
CREATE UNIQUE INDEX uq_active_assignment_per_courier
    ON dispatch.assignments (courier_id) WHERE status = 'ACTIVE';
```

A mocked repository cannot refuse the second insert. A test against a mock would therefore
verify that *the code we wrote* behaves as *we think it does* — a tautology — while the actual
enforcement mechanism went untested. Four of the six invariants are enforced by database
constraints, so for those, testing without a database tests nothing that matters.

This has a cost and it is worth stating: the integration suites need Docker, take ~60 s rather
than ~2 s, and were the source of a real environment fight (Docker API 1.32 vs Engine 29,
recorded in the bug log). That cost is accepted because the alternative is a fast suite that
proves the wrong thing.

**What *is* mocked:** domain ports in unit tests. The rule is *mock what you own the interface
to; use the real thing for what you do not.*

---

## Rule 3 — Assert on converged state

Chaos tests fail in a characteristic way: they assert on state *during* recovery, become
intermittently red, get an `@Disabled`, and the failure-correctness claim silently evaporates.
Nothing breaks when that happens — no build turns red, no metric moves. It is the highest-risk
silent failure in the project (threat E-03).

Two structural defences:

**Assert after a bounded settle window.** `scripts/chaos-run.sh` kills a container, waits for
convergence, and only then reads the database. It never asks "what is true right now" mid-kill,
because the correct answer during recovery is *a set* of acceptable states, not one state.

**Make silencing visible.** `@Disabled` anywhere under `proof/` **fails the build**, enforced by
`scripts/check-no-disabled-proofs.sh` in CI. Silencing a proof therefore requires deleting it,
in a commit, in a public repository. That guard has itself been tested by injecting a real
`@Disabled` and confirming a non-zero exit — an untested guard is untested code.

---

## What the chaos layer actually measures

The JUnit fault-injection suite and the shell chaos harness answer different questions, and
running only one of them would leave a real gap.

| | `proof/chaos` (Toxiproxy) | `scripts/chaos-run.sh` (real containers) |
|---|---|---|
| Failure modelled | Network partition, injected latency | SIGKILL — process death with no drain |
| Runs in CI | Yes | No (needs the live stack) |
| Proves | NFR-012 fail-closed; constraint holds without app logic | NFR-005 recovery time, zero data loss |
| Measured | pass/fail | **6.1 s recovery**, 0 orders lost |

**Latency injection matters as much as hard partition.** A system that behaves correctly when
the database is *gone* can still behave incorrectly when it is merely *slow* — cascading
timeouts cause more outages than clean failures. So the suite injects 3 s of latency, which is
slow enough to time out naive code and not slow enough to look like an outage.

The five real-container scenarios and why each exists:

| Scenario | Why this one |
|---|---|
| Kill `dispatch-service` | Holds the claim, the sweeper and the saga — the most destructive single failure available |
| Kill `order-service` | Added because review F-1 found its boundary was justified by a failure the plan never induced |
| Kill one `gateway` | The other must carry on; proves the instances are genuinely interchangeable |
| Kill `tracking-service` | Ingest must fail without touching the correctness path |
| Kill `redpanda` | The outbox must absorb the outage and drain afterwards, losing nothing |

The consistency check after every kill reads **the database, not the responses** — a service
can report success about state it never wrote. It includes one query that would not be obvious
to write and is the most revealing: *an `ACCEPTED` offer with no assignment behind it*, which
is precisely the half-committed state the claim's transaction exists to prevent.

---

## Coverage, and why there is no global target

Coverage is gated at **90% on `domain` only**, and nowhere else.

A global percentage target changes behaviour in a predictable and unhelpful way: effort moves
toward whatever is cheapest to cover — mappers, DTOs, getters — because that is how the number
goes up fastest. Meanwhile the code that carries the invariants is a few hundred lines whose
coverage barely moves the aggregate.

Where coverage is the wrong metric, the **invariant tests are the right one**, and they are
pass/fail rather than a percentage. "INV-1 held under 5,000 concurrent attempts" is a claim.
"87% line coverage" is not.

---

## The test pyramid, inverted on purpose

The conventional pyramid puts many fast unit tests at the base and few slow ones on top. This
project's distribution is deliberately different:

- **Unit tests are thin** because the domain is thin. The order state machine is genuinely
  worth exhaustive testing — it is tested over its entire transition space — but there is not
  much else in `domain` that can be wrong in an interesting way.
- **Integration tests carry the weight** because the interesting behaviour *is* the interaction
  with Postgres and Redis. Testing the claim without a database is testing a different thing.
- **The proof suites are the deliverable.** They are slow, they need Docker, and they are the
  reason the project exists.

That is not a pyramid, and pretending otherwise would be cargo-culting a shape whose rationale
(fast feedback on cheap logic) does not apply to a system whose difficulty is concentrated in
concurrency and durability.

---

## What the tests found

Seventeen bugs are recorded in `docs/bug-log.md`, generated from commit trailers. The
distribution is the most useful thing about it:

| Found by | Count | Character |
|---|---|---|
| Integration / concurrency suites | 5 | Correctness — ordering, idempotency, race classification |
| Running the real stack | 6 | Integration and environment — permissions, config, connectivity |
| Chaos and fan-out proofs | 3 | Only visible under failure or across instances |
| Own tooling | 3 | The guards and generators themselves |

**The most valuable single finding was an ordering bug that atomicity did not prevent**: order
creation inserted the order *before* claiming the idempotency key and returned early when the
claim lost — but returning normally commits, so 24 concurrent requests sharing one key created
24 orders. The transaction was correct; the *order of writes inside it* was not. A mocked
repository would have hidden it completely.

**The chaos suite found the failure it was built for**, in the place least expected: killing and
restarting a gateway left Caddy holding a stale upstream IP, so the proxy returned 502 while
both gateways were healthy and reachable directly. No unit or integration test could have
surfaced that — it only exists in the interaction between a restarted container and a proxy
that resolved DNS once.

---

## Known limits

Stated plainly, because a strategy document that lists only strengths is marketing.

| Limit | Consequence |
|---|---|
| **The bug log cannot detect a missing trailer.** CI detects a *stale* log, not an unwritten entry | Residual reliance on habit. Thinner than "remember to write a document", not zero |
| **No load test.** NFR-001's p99 is a target, not a measurement | Latency figures come from the contention harness, not a sustained-load profile. Stretch scope (ADR-0010) |
| **Three-source reconciliation is stretch.** Ground truth is emitted and compared; Kafka-from-offset-0 is not | The non-circularity claim holds at reduced strength, and the README says so |
| **Chaos runs manually.** It needs the live stack, so CI cannot run it | A regression in recovery behaviour would not be caught until the next manual run |
| **Determinism is asserted, not yet byte-compared across runs** | NFR-008's strongest form — two runs, byte-identical output — is designed for but unverified |
| **No frontend tests.** The map was cut to static polling (ADR-0011) | Negligible: there is almost no frontend left to test |

---

## Running them

```bash
./wassal.sh proof                 # unit + arch + integration + concurrency + durability
./gradlew :proof:chaos:test       # Toxiproxy fault injection
./wassal.sh chaos                 # kill real containers, measure recovery (needs the stack up)
./wassal.sh chaos dispatch        # one scenario
./gradlew :dispatch-service:test --tests '*RedisLockComparisonTest'   # the ADR-0004 benchmark
```

CI runs everything except the real-container chaos suite on every push to `main`, plus four
guards that are not tests but protect the tests: no `@Disabled` under `proof/`, no
infrastructure ports in the hosted overlay, every Compose service declares a memory limit, and
the bug log is not stale.
