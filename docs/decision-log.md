# Decision Log

Append-only record of architecture and technology decisions. Every entry is an ADR.

A superseded decision keeps its entry and has its status changed to
`Superseded by ADR-0NN`. Never delete or rewrite an entry — the reasoning that was
rejected is often the most useful thing in this file a year later.

Format and guidance are in the Project Forge SKILL.md.

**Project:** Wassal — real-time courier dispatch platform
**Started:** 2026-08-08

---

<!-- Append new ADRs below. Keep them in ascending ID order. -->

## ADR-0001: Architectural depth is the deliverable; complexity is deliberate

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 1

**Context**

Wassal is a portfolio and skills-development project. Its stated purpose is to demonstrate
distributed-systems engineering — concurrency control, effectively-once semantics, durable
timers, saga compensation, failure recovery — with measurable evidence. The user-facing
product (orders, couriers, a live map) exists to make those properties concrete.

Standard engineering judgment says a solo developer with ~240 hours should not run four
services, Kafka, Redis, Postgres and a full observability stack to move delivery jobs
between people. Measured against user-facing outcomes, that judgment is correct: a single
Spring Boot monolith with a Postgres advisory lock and server-sent events would ship the
same features in a fraction of the time.

That judgment does not apply here, because the user-facing outcome is not the objective
function. An architecture that ships the same features more simply is a **failed outcome**
for this project, not a win. Recording that inversion explicitly is the point of this ADR —
without it, every later reviewer (including future sessions of this planning process, and
any engineer reading the repository) will read the architecture as over-engineering by a
developer who did not know better.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| Modular monolith, Postgres only, SSE for live updates | Ships in ~60 h; trivially operable; genuinely the right call for a real product at this scale | Demonstrates none of the five properties the project exists to prove. No async boundary, no distributed claim, no cross-instance fan-out problem, no saga. Fails the actual objective |
| Full event-driven microservices as briefed | Every named challenge becomes real rather than hypothetical. Kafka operations, distributed locking and WebSocket scaling — the three stated learning targets — are exercised under genuine conditions | Roughly 4× the build cost. Nine containers to operate solo. Failure modes that only appear under distribution, on a developer with no prior Kafka operations experience |
| Middle path: two services, Kafka, no Redis, no observability stack | Cheaper; retains one async boundary | Retains the *appearance* of distribution while removing the parts that make it hard. The dedup, fan-out and hot-path problems all disappear. Worst of both — the operational cost without the learning payoff |

**Decision**

Build the full event-driven architecture as briefed: `order-service`, `dispatch-service`,
`tracking-service`, `gateway`, and `simulator`, over Kafka, Redis and PostgreSQL/PostGIS,
with OpenTelemetry, Prometheus and Grafana.

When scope pressure arrives — and the Phase 1 estimate of ~260 hours against a ~240-hour
budget says it will — the lever is **feature scope, never architectural depth**. The cut
order is recorded in `feasibility.md` under Scope Realism.

This decision does **not** license decorative complexity. Every service boundary and every
async boundary must pass a single test: *name the failure it isolates or the scaling axis it
unlocks.* Anything that cannot is merged or removed, and Phase 6 applies that test
explicitly to all five services and every Kafka topic. Complexity that is load-bearing is
kept whatever it costs; complexity that is present so the diagram looks impressive is cut.

**Reason for selection**

The constraint that decided it: **the project's objective function is demonstrated
engineering depth, not delivered features.** Under that objective, the simpler architectures
do not score lower — they score zero, because they contain nothing to demonstrate. The
operational burden and the build cost are real and are accepted knowingly as the price of
the only outcome that satisfies the goal.

Secondary consideration: the three stated learning targets (Kafka operations, distributed
locking, WebSocket scaling) are precisely the things the simpler options remove.

**Consequences**

*Becomes easy:* every challenge in the brief is real rather than simulated, so the evidence
produced is genuine. The five correctness properties become demonstrable with measured
numbers. The architecture supports the proof artifacts (concurrency, chaos, load,
reconciliation) natively — there is something to actually chaos-test.

*Becomes hard:* the operational surface is nine containers, maintained by one developer.
Debugging spans process boundaries from day one; a stack trace no longer tells the whole
story, which is why distributed tracing is not optional here. Local development friction is
a permanent tax, making a genuinely one-command `docker compose up` a hard requirement from
Sprint 1 rather than a convenience. Feature scope is permanently thin, and the README must
account for that so a reader does not mistake thin features for an unfinished project.

*Now expensive to reverse:* collapsing to a monolith later would discard the assignment
core's distribution-aware design (outbox, inbox dedup, cross-instance fan-out) — roughly the
30% of the codebase that carries the project's entire value. Reversal is not merely costly,
it is self-defeating. Conversely, *adding* depth later is cheap, so nothing here forecloses
future ambition.

*Accepted risk:* an engineer reading the repository without context may read this as
over-engineering. Mitigated by stating the objective explicitly at the top of the README and
linking this ADR.


## ADR-0002: Event-driven microservices over the modular-monolith default

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

ADR-0001 established that architectural depth is the deliverable. This ADR records the
specific decomposition that follows, and exists separately because the two decisions could
diverge: one could accept "depth is the goal" and still choose a different shape — a
modular monolith with a separate ingest worker, for instance, would be more depth than a
plain CRUD app and far less than four services.

The forces: five correctness properties must be *observable*, not merely implemented
(F-04); three named learning targets are Kafka operations, distributed locking and
WebSocket scaling (F-20); the budget is ~240 hours (C-2); and NFR-011 caps memory at 6 GB.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| Modular monolith + background worker | ~60 h. One deployable. Module boundaries preserve the split option. Genuinely correct engineering for the feature set | In-process calls cannot arrive twice, out of order, or after the caller died. Removes the conditions under which P-2, P-3 and P-5 are observable. No consumer-group semantics to learn, no cross-instance fan-out problem |
| Two services: `api` + `dispatch`, Kafka between | ~120 h. One real async boundary. Half the operational cost | Location ingest shares a process with assignment, so the write-amplification isolation (challenge 3.4) is a claim rather than a demonstration. WebSocket fan-out across instances never arises with one gateway. Two of seven challenges evaporate |
| **Four services + standalone simulator** | Every challenge in §3 becomes real. All three learning targets exercised under load and failure. Each boundary maps to a named failure | ~4× build cost. Nine containers on one developer. Debugging spans processes from day one |
| Six or more services (split courier from dispatch, notification service, etc.) | Even more surface | Fails ADR-0001's own test: a `courier-service` split from `dispatch-service` would put courier status and assignment insert in different transactions, **breaking the atomic claim** (ADR-0004) and forcing a distributed lock the project does not need. More services would make the system *less* correct |

**Decision**

Four services — `gateway`, `order-service`, `dispatch-service`, `tracking-service` — plus a
standalone `simulator` and an independent `reconciliation-job`. Kafka for domain events,
Redis Pub/Sub for ephemeral position fan-out, one PostgreSQL instance with schema-per-service
and no cross-schema foreign keys.

**Reason for selection**

The constraint that decided it: **each boundary must isolate a failure that the chaos suite
actually induces.** Under that test the four-service split is not arbitrary —

- `gateway` isolates connection state from business logic, and is the axis along which the
  cross-instance fan-out problem (challenge 3.5) exists at all. With one gateway, FR-016 is
  untestable.
- `tracking-service` isolates a 100 msg/s write path from the correctness path (challenge
  3.4). Its independent failure mode — stale positions degrading match quality silently — is
  a distinct and instructive one.
- `dispatch-service` is the unit that gets killed mid-assignment in the headline chaos
  scenario (NFR-005). It must be independently killable for that test to mean anything.
- `order-service` isolates order acceptance from dispatch availability, which is what makes
  "orders survive a dead dispatcher" demonstrable rather than asserted.

The fourth option is rejected on correctness grounds rather than cost, which is the useful
finding here: **more distribution would make this system worse.** Splitting courier state
from assignment state would move the atomic claim across a network boundary and require a
distributed lock — trading a correct, simple mechanism for a contested, complex one. That is
the precise line between load-bearing and decorative complexity.

**Consequences**

*Becomes easy:* every §3 challenge is real. The chaos suite has genuinely separable targets.
Two gateway instances prove fan-out. Learning targets are exercised, not simulated.

*Becomes hard:* nine containers. Cross-process debugging from day one, making distributed
tracing (NFR-010) mandatory rather than nice-to-have. Every domain change that spans
aggregates becomes an event contract change.

*Now expensive to reverse:* moderately, in one direction only. Collapsing to a monolith
would discard the outbox, inbox and fan-out layers — roughly 30% of the code and 100% of the
value. Splitting further is cheap but, per the above, undesirable.

*Known limitation, recorded deliberately:* schema-per-service in one Postgres instance is
not database-per-service. It does not prove the services could run against separate stores.
Accepted because four Postgres containers would spend memory NFR-011 does not have, and
because the atomic claim requires courier status and assignment insert to share a
transaction. Re-examined in Phase 6.

---

## ADR-0003: Redis GEO for the candidate index, PostGIS for history

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

Challenge 3.3 requires finding the nearest N available couriers within a radius, fast enough
to stay inside a 500 ms assignment budget (NFR-001), with a 50 ms sub-budget (NFR-002). The
brief asks for a choice between PostGIS, a Redis geo index, or a hybrid, justified against
the read/write ratio.

**That ratio is the decisive fact, and it is lopsided:**

| Operation | Rate | Source |
|---|---|---|
| Position writes | **100/s sustained** | NFR-003 — 300 couriers reporting every 3 s |
| Candidate searches | **~2.5/s** baseline, ~7.5/s at peak | 50 orders/min × ~3 search attempts per order (re-offers after decline/expire) |

**Roughly 40:1 write-to-read.** This is not a search index in the usual sense — it is a
write-mostly index that is occasionally queried. That inverts the usual reasoning, where
index maintenance cost is amortised across many reads.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| PostGIS only (`ST_DWithin` on a GiST index) | One store, one source of truth, no cache coherence problem. Rich spatial queries available. Transactionally consistent with the claim | **100 GiST index updates/s** is precisely the write amplification challenge 3.4 exists to avoid. Every position report becomes a heap update plus index maintenance plus WAL. Directly contradicts NFR-003's requirement that Postgres write rate be ≥10× *below* ingest rate |
| Redis GEO only (`GEOADD` / `GEOSEARCH`) | `GEOADD` is O(log N) in-memory; at N=300 both operations are sub-millisecond. Zero Postgres write cost. Trivially satisfies NFR-002 | No durability — a Redis restart empties the index. No spatial analysis over history. Cannot participate in the claim transaction |
| **Hybrid with explicit ownership** | Each store does what it is good at. Write-heavy index in memory, durable history in Postgres | Two representations of "where is this courier", which raises a coherence question that must be answered rather than hand-waved |
| PostGIS with an unlogged table | Cheaper writes than a logged table | Still index maintenance per write; still Postgres CPU. Solves the wrong half of the problem |

**Decision**

**Hybrid, with ownership that is explicit rather than implied:**

- **Redis GEO** holds the live index of *available* couriers only. `GEOADD` on position
  report, `ZREM` on going busy or offline, `GEOSEARCH` for candidates. It is a **cache** —
  rebuildable at any time from Postgres.
- **PostgreSQL + PostGIS** holds `location_history` (partitioned, batch-written on the cold
  path) and the authoritative `couriers.status`. PostGIS serves analysis and reconciliation,
  never the hot candidate path.
- **The claim never consults Redis.** Candidate search proposes; the Postgres conditional
  update disposes (ADR-0004).

**Reason for selection**

The constraint that decided it: **NFR-003 requires Postgres write rate to be at least an
order of magnitude below the 100 msg/s ingest rate.** PostGIS-only fails that requirement
arithmetically, not marginally — it is not a tuning problem.

The coherence question that a hybrid raises has a clean answer here, and it is the most
useful property in this design: **a stale Redis entry cannot cause an incorrect assignment.**
If the index returns a courier who has since gone busy, the conditional claim affects zero
rows, the offer attempt is abandoned, and the next candidate is taken. The cost of staleness
is one wasted round trip. This is why the index is permitted to be eventually consistent
while the claim is not — and it is why no effort is spent keeping them perfectly
synchronised, which would be the expensive and unnecessary alternative.

PostGIS is kept rather than dropped because the historical path genuinely needs spatial
queries for reconciliation and for the ground-truth comparison (FR-020), and because
`location_history` must be durable regardless.

**Consequences**

*Becomes easy:* candidate search comfortably inside its 50 ms budget with two orders of
magnitude of headroom. Postgres write load stays proportional to *orders*, not to
*positions* — the core requirement of challenge 3.4. Redis can be scaled or sharded by
geohash later without touching Postgres.

*Becomes hard:* two representations of courier position must be reasoned about. Index
rebuild-on-recovery is a code path that must exist and must be tested — and it is easy to
forget until Redis is restarted for the first time. The reconciliation job must account for
legitimate index/truth divergence rather than reporting it as variance.

*Now expensive to reverse:* not very. Moving to PostGIS-only would be a query change plus
accepting the write cost. The interface (`findNearestAvailable(point, radius, n)`) hides
which store answers, so this is a genuinely swappable decision — which is why it does not
warrant more design effort than it has received.

*Accepted risk:* a Redis restart empties the index and stops dispatch until rebuild. RTO
< 60 s, recorded in the failure-recovery table. Rebuild-on-startup is a required feature,
not an optional one.

**Amendment 2026-08-08 (Phase 6 review, finding F-3)** — the decision stands; its
*application* was corrected. This entry originally said Redis holds the live index of
"available couriers only", which would have required `tracking-service` to know courier
availability — state owned by `dispatch-service` and off-limits under the module boundary
rules. The corrected split: `tracking-service` owns `geo:couriers` (every courier's
position), `dispatch-service` owns `set:available`, and candidate search intersects them.
Redis-versus-PostGIS is unchanged, so no superseding ADR is warranted; had the finding
invalidated that choice, a new ADR would have been required rather than this note.

---

## ADR-0004: Atomic claim in Postgres, not a distributed lock

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

Challenge 3.1 and the project's headline requirement: two orders arrive in the same
millisecond wanting the same courier, and exactly one may claim them. `if (available)`
followed by an update is wrong. The brief asks for either a conditional update with an
affected-row check or a Redis atomic primitive, with the reasoning stated.

This decision also sits against learning-target rule F-20 — **distributed locking is one of
three named gaps**, which creates genuine tension with the correctness analysis below.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| **Postgres conditional UPDATE + affected-row check** | Atomic with the assignment insert in one transaction. No second system to fail. Backed by a partial unique index, making the invariant structural. Standard, boring, provably correct | Contention is row-lock contention; hot couriers serialise. No exotic learning |
| Redis Lua script (`SET NX` / atomic script) | Genuinely atomic in Redis. Very fast. Exercises the distributed-locking learning target directly | **The claim would be in Redis, the assignment in Postgres.** If the process dies between the two, the courier is locked with no assignment — requiring a lock TTL, which requires deciding what happens when the TTL expires mid-assignment. Introduces a correctness problem the Postgres option does not have |
| Redlock across multiple Redis instances | The canonical "distributed lock" | Contested for exactly this use case (Kleppmann's fencing-token critique). Requires multiple Redis nodes the project does not run. Solves a problem that only exists because the lock was placed outside the transactional store |
| Postgres advisory lock | No schema change; explicit lock semantics | Lock is not tied to row state, so it protects the critical section without making the invariant structural. Strictly weaker than a conditional update plus unique index |
| Serializable isolation on the whole transaction | Strongest correctness guarantee | Serialisation failures require retry logic everywhere; heavy for a problem a single conditional predicate solves |

**Decision**

The claim is a **conditional `UPDATE` inside the assignment transaction**, with the
affected-row count as the decision:

```sql
BEGIN;
  UPDATE offers   SET status = 'ACCEPTED'
   WHERE id = :offerId AND status = 'OFFERED' AND expires_at > now();
  -- 0 rows → 410 OFFER_EXPIRED, rollback

  UPDATE couriers SET status = 'BUSY'
   WHERE id = :courierId AND status = 'AVAILABLE';
  -- 0 rows → 409 COURIER_UNAVAILABLE, rollback

  INSERT INTO assignments (...) VALUES (...);
  INSERT INTO outbox      (...) VALUES (...);
COMMIT;
```

Backed by two **partial unique indexes** that make INV-1 and INV-2 structural:

```sql
CREATE UNIQUE INDEX uq_active_assignment_per_courier
    ON assignments (courier_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_active_assignment_per_order
    ON assignments (order_id)   WHERE status = 'ACTIVE';
```

No distributed lock. No Redis primitive on the claim path.

**Reason for selection**

The constraint that decided it: **the claim must be atomic with the assignment insert**, and
only the transactional store can provide that. Every option that places the claim outside
Postgres reintroduces a two-phase problem — claim here, write there, die in between — and
then requires a TTL and a compensation path to fix a problem that the transactional option
never has. Choosing Redis for the claim would be trading a solved problem for an interesting
one.

The partial unique indexes are the part worth emphasising. They mean **INV-1 cannot be
violated by a bug in the service** — if the conditional update logic were wrong tomorrow, the
database would refuse the second insert. That converts the headline invariant from "enforced
by code we tested" to "enforced by a constraint that cannot be bypassed," which is a
materially stronger claim to make in a README.

**On the learning-target tension (F-20).** The rule says: where a choice is close, favour the
option that teaches more. This choice is **not close** — the Redis variants are less correct,
not merely different. But the learning target is served better by this outcome than by the
alternative, and honestly so: *knowing when not to reach for a distributed lock is the more
valuable half of understanding distributed locking.* Kleppmann's critique of Redlock is
precisely the argument that a lock outside the store that holds the data cannot give you
mutual exclusion without fencing tokens. Having made that argument concretely, against a
real design, is a better portfolio artifact than having implemented Redlock.

To keep the learning genuinely exercised rather than merely reasoned about, a **comparison
spike** is scheduled as a Could-have: implement the Redis-Lua claim variant in an isolated
harness, benchmark both under the stress profile, and kill the process mid-claim to
demonstrate the orphaned-lock failure empirically. If it ships, it is one of the strongest
sections in the README. If it is cut, nothing in the system depends on it.

**Consequences**

*Becomes easy:* correctness is straightforward and testable. No lock lifetime, no TTL tuning,
no fencing tokens, no clock-skew reasoning. The claim path has one failure mode — zero rows
affected — with one defined response.

*Becomes hard:* contention on hot couriers becomes row-lock contention, serialising under the
stress profile. This is the system's real scaling ceiling (~2 k claims/s) and it is
deliberately left in place, since exploring it is the point. Every claim requires a Postgres
round trip, spending ~10 ms of the 500 ms budget.

*Now expensive to reverse:* the partial unique indexes are load-bearing. Moving the claim
elsewhere would require dropping them and re-establishing the invariant in application code —
a strict downgrade. Effectively a one-way door, entered knowingly.

**Amendment 2026-08-09 (pre-Sprint-1 review, item 3)** — the decision is unchanged; the
*comparison spike* it describes has been promoted from a Sprint 5 could-have to a **scheduled
Sprint 2 task, timeboxed to 4 hours** (`S2-12`), positioned immediately after
`AtomicClaimExecutor` while that code is still fresh.

Reasoning for the promotion: **distributed locking is one of the three stated learning targets
of the entire project** (F-20), and this ADR resolved it by choosing *not* to use a distributed
lock. That is the correct engineering answer, but left as a could-have in the final sprint of
an over-budget plan it would never have been executed, and the learning target would have been
closed on reasoning alone.

Scope is a **written comparison, not a shipped alternative**: a Redis Lua claim script in a
test source set only (never wired into a production path), benchmarked against the Postgres
claim under the `Inv1DoubleAssignmentTest` contention profile for throughput and p99, plus a
step-by-step written statement of the exact crash window that decided this ADR. **The result is
appended below this note as evidence under the decision it supports.**

Rationale worth recording: "I chose Postgres partial unique indexes over a Redis distributed
lock, here is the crash window that decided it, and here is the benchmark of both" is a
stronger artifact than either implementation alone. Open question U-2 is closed as *scheduled*
rather than optional.

**S2-12 RESULT (2026-08-09).** Spike executed in Sprint 2 as scheduled. Source:
`dispatch-service/src/test/java/dev/wassal/dispatch/RedisLockComparisonTest.java` — test source
set only, never wired into a production path.

**Benchmark**, 500 contended attempts, 20 couriers, 25 attempts each, released simultaneously:

| Variant | Total | Per attempt | Won | Lost |
|---|---:|---:|---:|---:|
| Redis `SET NX PX` | 46.6 ms | **0.093 ms** | 20 | 480 |
| Postgres conditional `UPDATE` | 470.1 ms | **0.940 ms** | 20 | 480 |

**Redis is roughly 10× faster per attempt, and the decision is unchanged.** Publishing a number
that flatters the rejected option is the point of measuring rather than asserting: throughput was
never the reason for this ADR, and at the design target of ~0.8 claims/s a 0.94 ms claim is four
orders of magnitude inside budget. Both variants produced exactly one winner per courier, so on
the happy path they are indistinguishable — which is precisely what makes the lock tempting.

**The crash window, which is what actually decided it.** Verified by
`crashBetweenClaimAndAssignment`:

```
Redis lock                                   Postgres conditional update
-----------------------------------------    -----------------------------------------
1. SET lock:courier:X NX PX 30000  -> OK      1. BEGIN
2. <<< PROCESS DIES >>>                       2. UPDATE couriers SET status='BUSY'
   Lock survives. Courier is claimed,            WHERE id=X AND status='AVAILABLE'
   no assignment exists, and nothing in       3. INSERT assignment
   Postgres records the attempt.              4. <<< PROCESS DIES >>>
3. Released only when the TTL expires.           Rollback. Courier AVAILABLE again.
                                                 No orphan, no TTL, no cleanup job.
```

The test asserts both halves: the Redis lock is still held with a positive TTL after the holder
dies, while the Postgres courier is back to `AVAILABLE` with no compensating action taken.

**The TTL has no correct value.** It must exceed the longest possible assignment or mutual
exclusion breaks; it must be short enough that a crash does not strand a courier for minutes.
Those constraints do not both have a solution, so the lock variant would need fencing tokens —
Kleppmann's argument, reduced to this specific claim. The transactional variant has no such
parameter, because the store that holds the data is the store that arbitrates access to it.

**Learning target G-5 (distributed locking) is closed by this comparison**, not by the ADR's
reasoning alone. Knowing when *not* to reach for a distributed lock — and being able to show the
crash window and the benchmark — is the more valuable half of understanding one.

---

---

## ADR-0005: Durable expiry via persisted deadline plus sweeper

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

Challenge 3.2: an offer has a 15 s response window. An in-process timer is unacceptable —
if the service dies at second 7, the order hangs forever, violating INV-4. Expiry must
survive process death, fire within ±1 s (NFR-004), and resolve deterministically against a
concurrent accept (FR-012).

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| In-process `ScheduledExecutorService` | Trivial; exact timing | Dies with the process. Explicitly forbidden by the brief. Also wrong across multiple instances — every instance would fire |
| **Persisted `expires_at` + polling sweeper** | Deadline is durable and queryable. Survives any restart. Multi-instance safe via `FOR UPDATE SKIP LOCKED`. The deadline is available as a SQL predicate, which FR-012 requires | Polling cost; accuracy bounded by tick interval |
| Redis sorted set, score = deadline, `ZRANGEBYSCORE` sweeper | Fast; no Postgres load | Redis is not durable here (cache role, ADR-0003). A Redis restart loses every pending deadline — the exact failure this requirement exists to prevent. Would also split the deadline from the row the accept predicate must check |
| Kafka delayed messages | Fits the event backbone | **Kafka has no native delay.** Emulation requires per-delay-tier topics with a forwarding consumer, or an external scheduler — inventing a scheduler to avoid writing a scheduler. Delivery timing is also weaker than ±1 s under rebalance |
| Quartz / ShedLock | Purpose-built, cluster-aware | Another dependency and schema for what one indexed query provides. Still needs `expires_at` on the row for the accept predicate, so the row-based deadline exists either way |

**Decision**

`offers.expires_at TIMESTAMPTZ NOT NULL`, written in the same transaction as the offer, with
a partial index on `(expires_at) WHERE status = 'OFFERED'`. A sweeper in `dispatch-service`
ticks every **250 ms**:

```sql
UPDATE offers SET status = 'EXPIRED', expired_at = now()
 WHERE id IN (
   SELECT id FROM offers
    WHERE status = 'OFFERED' AND expires_at <= now()
    ORDER BY expires_at
    LIMIT 100
    FOR UPDATE SKIP LOCKED)
RETURNING id, order_id, courier_id;
```

Multi-instance safe by `SKIP LOCKED` — deliberately **without leader election**, since
leader election would be a coordination mechanism added to a problem that does not need one.
All time comparisons use database `now()`, never instance wall-clock, so clock skew between
instances is irrelevant by construction.

**Reason for selection**

The constraint that decided it: **FR-012 requires the deadline to be a predicate in the
accept's `WHERE` clause**, not merely a trigger for a background job. That single requirement
eliminates every option that stores the deadline outside the row being updated — Redis ZSET,
Kafka delays and Quartz all leave the accept path unable to check expiry atomically, which is
exactly where the race lives.

Once the deadline must be a column, a sweeper over that column is the obvious and smallest
mechanism. The 250 ms tick gives ±250 ms nominal accuracy against a ±1 s requirement, leaving
4× headroom for scheduling jitter and long batches.

The `SKIP LOCKED` pattern is a deliberate secondary benefit: it demonstrates multi-instance
work distribution without coordination, which is directly relevant to the distributed-systems
subject matter and costs nothing extra.

**Consequences**

*Becomes easy:* restart durability is free — the deadline is a row, and a restarted sweeper
immediately catches everything overdue. Expiry-versus-accept resolution becomes a single
conditional update (FR-012) with no coordination. Sweeper lag is trivially measurable as
`now() - min(expires_at)` over unexpired overdue offers.

*Becomes hard:* a constant background query load of 4 queries/s per instance, mostly
returning nothing. Accuracy is quantised to the tick interval, so ±1 s is achievable but
±100 ms would require a tighter loop and more empty queries. A long GC pause in the sweeper
directly delays expiry — which is why sweeper lag is a first-class gauge with an alert
rather than an internal detail.

*Now expensive to reverse:* barely. Swapping the sweeper's trigger mechanism is contained;
the durable column stays regardless, since the accept predicate needs it.

---

## ADR-0006: Polling outbox publisher; CDC deferred

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

F-08 mandates a transactional outbox at the DB→Kafka boundary and asks for an explicit
choice between a polling publisher and CDC, noting "CDC is more correct and heavier; call
it."

Worth correcting one premise before deciding: **CDC is not more correct here.** Both
approaches deliver at-least-once with no loss, because in both the durability boundary is
the outbox row committed inside the state-change transaction. CDC's advantages are latency
and the absence of polling load — real, but not correctness. The genuine tradeoff is
latency and operational learning against container budget and hours.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| **Polling publisher** | ~50 lines. No new container. Multi-instance safe with `FOR UPDATE SKIP LOCKED`. Publisher lag directly observable and tunable | Poll interval adds latency to the assignment path. Empty queries when idle. Publisher is code that must be correct |
| CDC via Debezium + Kafka Connect | Near-zero lag; no polling load. Teaches Kafka Connect, logical replication and WAL mechanics. Publisher correctness moves into a battle-tested product | **Two more containers** (Connect worker, and realistically a schema registry) at ~1 GB against a 6 GB cap (NFR-011). Configuration surface is large. Debugging a misconfigured connector is a genuine time sink for someone with no Kafka operations background — the exact profile here (F-20) |
| Direct publish after commit, no outbox | Simplest | The dual-write problem the outbox exists to solve. Non-negotiable — INV-5 depends on it |
| Postgres `LISTEN`/`NOTIFY` to wake the publisher | Removes idle polling; sub-10 ms wake-up | Notifications are **not durable** — one lost during a disconnect means an event waits for the fallback poll. Fine as an optimisation over polling; unsound as a replacement |

**Decision**

**Polling publisher** in each service that owns an outbox (`order-service`,
`dispatch-service`), running every **100 ms**, batch size 100, ordered by `(aggregate_id,
created_at)` to preserve per-aggregate ordering, claiming rows with `FOR UPDATE SKIP LOCKED`
for multi-instance safety. Kafka acks set to `all`; the row is marked sent only after the
broker acknowledges.

CDC is **deferred, not rejected**, with the migration path documented: Debezium reads the
same outbox table via the outbox-event-router SMT, so the table schema is designed to be
CDC-compatible from day one (`aggregate_type`, `aggregate_id`, `event_type`, `payload`
columns matching the router's expected shape). Switching later requires no schema change and
no service code change — only Connect configuration and deleting the publisher.

**Reason for selection**

The constraint that decided it: **NFR-011's 6 GB memory cap against an already nine-container
stack.** Kafka Connect plus a schema registry is roughly 1 GB and two more health checks on
a stack whose cold-start time is itself a requirement (NFR-007). Polling costs 100 ms of a
500 ms budget that has 220 ms of headroom (see the architecture's budget decomposition) —
affordable, and measurable if it turns out not to be.

On the learning-target rule (F-20): this is the one decision in Phase 5 where the rule was
**overridden rather than applied**, and it should be visible as such. CDC would teach more —
specifically Kafka Connect and logical replication. Three reasons it still loses: the stated
learning target is *Kafka operations* (producers, consumers, partitions, consumer groups,
offset management), all of which are exercised identically either way; Connect is adjacent
rather than central to that; and the polling publisher itself teaches something non-trivial
that CDC hides — multi-instance work claiming with `SKIP LOCKED`, and the discipline of
marking sent only after broker acknowledgement.

The deferral is genuine rather than diplomatic. **The CDC-compatible table shape is a real
design constraint accepted now** to keep the option cheap, and if the project runs ahead of
schedule, switching to Debezium is a well-scoped Sprint 5 stretch item that would itself
make a good README section.

**Consequences**

*Becomes easy:* no new containers. Publisher lag is a single gauge. Local development stays
inside the memory budget. The publisher is small enough to unit-test exhaustively, including
the crash-after-publish-before-mark case.

*Becomes hard:* ~100 ms of avoidable latency on every dispatch — the largest single line in
the latency budget. Constant empty queries when idle (10/s per service). Publisher
correctness is now project code: the crash-between-publish-and-mark path must be handled and
tested, and is the source of the at-least-once duplicates FR-014 must absorb.

*Now expensive to reverse:* deliberately not. The CDC-compatible outbox schema means the
switch is configuration plus deletion, which is the entire reason for constraining the schema
now.

---

## ADR-0007: Redis Pub/Sub for position fan-out; Kafka for domain events

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

Challenge 3.5: a customer's WebSocket is on gateway instance A; the location update is
produced on instance B. Something must connect them. The system already runs Kafka, so using
it for positions would mean one fewer mechanism — the question is whether that is the right
call.

The volumes differ by two orders of magnitude: 100 position messages/s against roughly 2–5
domain events/s. So does the required delivery guarantee, and that is the deciding factor.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| **Redis Pub/Sub, channel per order** | Fire-and-forget broadcast is exactly the semantic needed. Sub-millisecond. No offsets, no consumer groups, no state per gateway instance. Gateway instances become genuinely stateless and disposable | At-most-once: a message published while a gateway is disconnected is gone. No replay |
| Kafka topic consumed by every gateway | One mechanism instead of two. Durable and replayable | **Consumer groups fight this use case.** Broadcast to all instances requires a unique consumer group per instance, which means per-instance offset state in the broker — for ephemeral processes that come and go. Offsets accumulate for dead instances. Every gateway also consumes every courier's positions regardless of subscriptions: 100 msg/s × N instances, filtered client-side. Durability is spent on data that is worthless one second later |
| Kafka with a shared group + gateway-to-gateway forwarding | Avoids per-instance groups | Reinvents Pub/Sub over a partitioned log, badly. Adds a routing hop and a new failure mode |
| WebSocket sticky sessions at the load balancer | No fan-out layer at all | Deletes the problem instead of solving it — and challenge 3.5 is a stated learning target (F-20). Also fails when a courier's subscribers span instances, which is the normal case |
| Redis Streams | Durable, with consumer groups | Durability for data with a 3 s useful life. Consumer-group complexity returns |

**Decision**

**Two mechanisms, chosen by data class rather than by convenience:**

| Data class | Mechanism | Guarantee | Why that guarantee is right |
|---|---|---|---|
| Domain events (order, offer, assignment) | Kafka, keyed by `orderId` | At-least-once, ordered per key, durable, replayable | Losing `AssignmentCreated` breaks INV-5 and corrupts reconciliation. Must not be lost |
| Courier positions | Redis Pub/Sub, channel `loc:order:{orderId}` | At-most-once, unordered, ephemeral | Losing one position is invisible — the next arrives in ~3 s. Positions are **current state, not history** |

Gateway subscribes only to channels for orders it currently has WebSocket subscribers for,
so fan-out is proportional to interest rather than to total traffic.

**Reason for selection**

The constraint that decided it: **positions are current-state, not an event stream.** Once
that is stated, most of the alternatives disqualify themselves — replaying missed positions
would show a courier retracing a path they have already travelled, which is worse than
showing nothing. FR-016 already specifies latest-wins on reconnect for exactly this reason.

Using Kafka here would mean paying for durability, ordering and replay on data whose value
expires in three seconds, and paying it in the currency the design can least afford:
per-instance consumer-group state on processes designed to be disposable.

The broader principle, worth stating because it recurs: **choose the delivery guarantee per
data class, not per system.** A single mechanism used everywhere is simpler to describe and
worse in practice — over-guaranteeing ephemeral data costs as much as under-guaranteeing
durable data.

**Consequences**

*Becomes easy:* gateway instances are stateless and horizontally scalable, which is the
scaling axis the component exists to unlock. Fan-out latency is sub-millisecond, so the 1 s
end-to-end target (NFR-006) is dominated by ingest, not distribution. No consumer-group or
offset management for ephemeral consumers.

*Becomes hard:* two messaging mechanisms to understand, operate and explain. Position loss
during a Redis blip is silent and permanent — mitigated by the staleness marker in FR-016, so
a client sees "stale" rather than a frozen map. Redis becomes load-bearing for the real-time
experience, though not for correctness.

*Now expensive to reverse:* not at all. The gateway's subscription registry is behind an
interface; swapping the transport is contained. Correctly identified as a cheap decision,
and given proportionate design effort.

---

## ADR-0008: Orchestrated saga with persisted state, not choreography

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 5

**Context**

Challenge 3.6: cancellation after acceptance must return the order to the pool, release the
courier, notify the customer and recompute the ETA — across service boundaries, with no
distributed transaction. Every compensating action must be idempotent and retry-safe, and
the brief requires it modelled explicitly as a state machine.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| Choreography — each service reacts to events | No coordinator. Loosely coupled. Fashionable | **"Where is this saga right now?" becomes unanswerable.** No single place holds progress, so resuming after a crash means reconstructing state by replaying events across services. A partial failure leaves no record that a saga was in flight. Directly contradicts the brief's requirement for an explicit state machine |
| **Orchestration with persisted saga state** | Progress is a row. Crash recovery resumes from the last completed step. The state machine is explicit and inspectable. Compensation order is defined in one place | The orchestrator is a component that can itself fail — mitigated precisely because its state is persisted |
| Distributed transaction / 2PC | Actually atomic | No 2PC across Postgres, Redis and Kafka. Would not work even if it were desirable |
| Do nothing — best-effort cancellation | Cheapest | Violates INV-6 and abandons challenge 3.6 |

**Decision**

An **orchestrated saga owned by `dispatch-service`**, with state persisted in a `sagas`
table: `(id, saga_type, aggregate_id, current_step, status, trigger_event_id, created_at,
updated_at)`, and a unique constraint on `(aggregate_id, saga_type, trigger_event_id)` so a
duplicated trigger cannot start two sagas.

Steps and compensations are declared as an explicit ordered list. On startup,
`dispatch-service` scans for sagas in a non-terminal state and resumes them from
`current_step` — never from the beginning, since re-running completed steps depends on
idempotency for correctness rather than for safety.

A significant part of the cancellation saga is local: assignment cancellation, courier
release and geo-index re-add all happen in **one Postgres transaction**. Only the
cross-service portion — returning the order to the pool via `order-service` — is genuinely
distributed.

**Reason for selection**

The constraint that decided it: **crash-mid-saga resumability is a stated requirement**
(FR-009, NFR-005), and choreography cannot provide it without reconstructing distributed
state. A persisted `current_step` turns recovery into a query.

The second reason is honesty about the shape of the problem. Choreography would produce a
more impressive diagram — four services reacting to each other's events — while making the
system harder to reason about and its recovery unprovable. Since the compensation is mostly
local, drawing it as a four-hop distributed dance would be **decorative complexity of exactly
the kind ADR-0001 forbids.** The saga is smaller than it looks, and the design says so.

**Consequences**

*Becomes easy:* `SELECT * FROM sagas WHERE status != 'COMPLETED'` answers "what is in
flight". Stuck sagas are a Prometheus gauge. Recovery is resumption, not replay. The state
machine is testable in isolation, and each compensating step is unit-testable for
idempotency.

*Becomes hard:* `dispatch-service` becomes the coordinator and therefore a more central
component — its failure stops saga progress, though never saga *correctness*, since state is
durable. Every new cross-service workflow must be modelled explicitly rather than emerging
from event subscriptions.

*Now expensive to reverse:* moderately. Moving to choreography would mean deleting the saga
table and distributing the logic — and losing resumability, which is the property that made
the decision. Effectively a one-way door, entered knowingly.

*Accepted limitation:* a saga that exhausts retries is marked `FAILED_NEEDS_ATTENTION` and
counted, with no automated resolution. For a system with no operator on call, a loud metric
is the correct terminal state — silently retrying forever would be worse.

---

## ADR-0009: Hetzner CX32 for the optional hosted demo, deferred to Sprint 5

- **Date:** 2026-08-08
- **Status:** Accepted
- **Phase:** 10

**Context**

F-18 set a ≤ €10/month ceiling for any hosted demo and asked for a reduced profile if the
full stack would not fit. Phase 1 gave a directional read that it would not — that ~4 GB of
always-on VPS costs €15–25/month.

**Current pricing contradicts that read.** As of 2026-08-08, an 8 GB / 4 vCPU Hetzner CX32
is ~€6.80/month, which accommodates the full ~5.6 GB stack including both gateway instances,
the server-side simulator and the observability tier. The reduced profile the brief
anticipated is unnecessary.

Two further facts shape the decision. Phase 9 established **exhibition mode** (A-6): with
asserted identity, a public write surface is trivially compromisable, so the hosted profile
is read-only and the simulator drives state server-side. And Phase 3's R-10 analysis
established that the README and the measured numbers matter more to a Reader than a live
link — the hosted demo is real value, but it is the *lowest value per hour* in the project.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| **Hetzner CX32, ~€7.80/mo all-in** | Runs the **complete** architecture with nothing dropped. EU. 20 TB traffic. Zero lock-in — plain Compose on plain Linux | You own the OS. ~1 h/month. Prices rose sharply in June 2026 and could again |
| Oracle Cloud Free Tier (4 ARM cores, 24 GB) | Genuinely €0. Four times the RAM needed | ARM64 builds needed for every image. Always-free capacity is frequently unavailable by region, and instances have been reclaimed for inactivity. **Costs ~2× the ops hours** of Hetzner. A demo that might vanish is worse than no demo, because the README links to it |
| Self-host + Cloudflare Tunnel | €0 compute, hardware already owned | Needs an always-on machine; a laptop is not one. Home broadband becomes the demo's SLA. Looks free, is not — it trades money for reliability exactly where reliability matters |
| Managed platforms (Railway, Render, Fly, DO) | Less operations | €25–45/month for six always-on services. Render's free tier sleeps, which is fatal for a link in a README |
| **No hosted demo — recorded walkthrough only** | €0, 2 h, nothing to break, cannot go down before an interview | A Reader must clone and run to see anything live, and most will not |

**Decision**

Two parts, meant to be read together:

1. **Provider: Hetzner CX32**, ~€7.80/month including a domain, running the full stack in
   exhibition mode. No reduced profile; nothing is dropped.
2. **Timing: deferred to Sprint 5, and optional.** The committed deliverable is a **recorded
   walkthrough** in the README (~2 h, €0). The hosted instance is provisioned only if Sprint
   5 has capacity, or on demand ahead of a specific interview.

**Reason for selection**

The constraint that decided the provider: **the demo exists to make the architecture legible
(R-10), so a hosted version missing pieces of that architecture would undercut its own
purpose.** Hetzner is the only option that runs everything — both gateways, the simulator,
the observability tier — inside the budget with no asterisk in the README.

Oracle's free tier is the right answer for someone optimising euros and the wrong answer
here. Read the TCO table in the currency that is actually scarce — hours, from a 240-hour
budget — and Oracle costs roughly double Hetzner over three months, plus a real risk that the
instance is unavailable when it matters. **The cheapest option is not the cheapest option.**

The constraint that decided the timing: the feature-cut ladder in `feasibility.md`, applied
honestly. Infrastructure polish is cut before proof artifacts, and hosting is infrastructure
polish. Standing up a VPS in Sprint 1 would spend scarce hours on a system that does not yet
do anything worth showing.

**Consequences**

*Becomes easy:* the project has a live demo path that costs under €8/month and can be
provisioned in ~3 hours from decision, because the Compose overlay is already specified.
Nothing about the local experience depends on it.

*Becomes hard:* a hosted instance is a thing that can break unattended, and there is no
on-call. Mitigated by exhibition mode — a read-only surface driven by a server-side simulator
has very little that can go wrong from outside — and by the explicit shutdown trigger below.

*Now expensive to reverse:* **not at all, and that is the point.** Plain Docker Compose on
plain Linux, no managed services, no vendor SDK, no proprietary configuration. Migration is
copying a directory and changing a DNS record. The hosting decision deliberately received
less design effort than ADR-0004 because it is worth less to get exactly right.

*Explicit shutdown trigger:* if the hosted instance consumes more than 2 hours in any month,
shut it down and fall back to the recording. Ops hours from this budget are worth more than
a live link.

*Accepted risk:* Hetzner raised prices up to 3.1× in June 2026. A further rise of that
magnitude would breach the €10 ceiling and trigger a move — cheap, per the above.


---

## ADR-0010: Committed scope is v0.4.0; Sprint 5 is stretch

- **Date:** 2026-08-09
- **Status:** Accepted
- **Phase:** 12 (pre-Sprint-1 amendment, item 1)

**Context**

The Phase 12 plan committed to five sprints totalling 245 h against a stated 200–240 h budget,
and treated the 5–45 h gap as manageable. Two compounding facts invalidate that reconciliation:

**1. The estimate is bottom-up from work never performed.** The project carries three named
experience gaps (F-20): Kafka operations, distributed locking, WebSocket scaling. Unfamiliar
work runs 1.5–2× its estimate as a rule, not as a worst case. Applying the multiplier
*selectively* — to the ~65 h of gap-touching tasks rather than uniformly — plus ~15% general
first-time friction on the remainder, the original 245 h becomes roughly **325 h**.

**2. The 20 h/week assumption was wrong.** The developer is a full-time master's student with
parallel commitments (daily English practice, sustained open-source contribution, technical
reading). Realistic sustained availability is **12–15 h/week**; 13 h/week is the planning
figure.

At 325 h and 13 h/week the original scope is **25 weeks**, not 10–12. The failure mode this
produces is not a Sprint 5 overrun — it is abandonment around week 11 with the project
two-thirds finished, which is precisely risk R-1 ("motivation decay at ~70%") already recorded
in the residual risk table. The plan was walking into its own named worst case.

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| Keep five sprints, extend the calendar to ~25 weeks | Nothing is given up | Six months on one portfolio artifact with no committed milestone before the end. R-1 fires around week 11 with nothing tagged as "done" |
| Keep five sprints, cut architecture to fit | Fits the calendar | Forbidden by ADR-0001, and correctly so — the depth *is* the deliverable |
| **Commit to v0.4.0 (Sprints 1–4 + core proof), make the remainder stretch** | A defensible, complete artifact at a fixed date. Every invariant proven, chaos proof landed, README written. Stretch items are genuinely additive rather than load-bearing | The three-source reconciliation job and the k6 load report may not ship |
| Commit to v0.3.0 (end of Sprint 3) | Safest | Loses the simulator, the real-time layer and the WebSocket learning target — one of the three stated gaps. Too much |

**Decision**

**Committed scope is `v0.4.0`: Sprints 1–4 plus the core proof subset** — chaos suite,
invariant counters for INV-4…INV-6, the README, the bug story, and the demo script. Estimated
**≈221 h nominal / ≈293 h realistic**, landing at **~24 weeks**.

**Sprint 5's remainder is re-labelled stretch**, pulled in only if velocity allows: the
three-source reconciliation job (FR-017), the k6 load report (NFR-001 measurement), and the
hosted demo (now dropped outright — see Consequences).

All five named cuts from `feasibility.md` are taken **now** as baseline scope rather than held
as contingency, with two corrections recorded separately in ADR-0011.

**Reason for selection**

The constraint that decided it: **R-1 is the project's highest-rated residual risk, and the
original plan had no committed milestone before month six.** A plan whose first "done" moment
is 25 weeks away is a plan that will be abandoned by a developer whose availability is already
being contested by a full-time degree.

v0.4.0 was already argued in the Phase 12 scope reconciliation as "a defensible artifact:
proven invariants, working real-time layer, measured numbers — missing only the reconciliation
job and some polish." This ADR promotes that from contingency to plan. The difference matters:
a contingency is a thing you fall back to in failure, and a plan is a thing you finish.

**Consequences**

*Becomes easy:* there is a fixed, reachable definition of done at ~24 weeks. Every sprint
boundary is a tagged release. Stretch items are genuinely optional, so pulling one in is a
success rather than pulling one out being a failure — the psychological direction is reversed,
which is the entire point.

*Becomes hard:* **the two things at risk are the reconciliation job and the load report**, and
both matter. Honest accounting of what replaces them if they do not land:

| At risk | What replaces it in the README |
|---|---|
| **FR-017 three-source reconciliation** | The **ground-truth comparison (FR-020) remains committed scope** and is deliberately kept Must for this reason. The README states: *"System state was validated against ground truth emitted independently by the simulator — a component that shares no code, schema or connection with the services under test. The fuller three-source reconciliation against Kafka consumed from offset 0 is specified in `docs/05-data-model.md` and `docs/architecture-review.md` finding F-2, and is not built."* This preserves the non-circularity claim (threat E-02) at reduced strength, stated plainly rather than glossed |
| **k6 load report (NFR-001 p99)** | Latency is still *measured* — the Prometheus histogram from the concurrency suite gives real p99 numbers under contention, which is the more interesting condition anyway. What is lost is a sustained-throughput profile at 50 orders/min. README states which figures came from the contention harness rather than a dedicated load run, and marks NFR-001 as **not formally measured** |
| Hosted demo | Already optional under ADR-0009; now dropped. The recorded walkthrough is the committed deliverable, closing open question U-1 |

Both replacements are weaker than the originals, and **saying so explicitly is required** —
F-14 mandates publishing misses, and a quietly-substituted weaker proof is exactly the
self-deception threat this project models as Adversary 2.

*Now expensive to reverse:* not at all. Stretch items can be pulled in at any sprint boundary
if velocity beats the estimate.

*Accepted risk:* 13 h/week is itself an estimate. If actual availability is 10 h/week, v0.4.0
lands at ~30 weeks and Sprint 4 becomes the natural stopping point instead. The 2× rule
(ADR-0011's companion, recorded in `coding-standards.md`) is the mechanism that surfaces this
while the project is running rather than at the end.

---

## ADR-0011: Two corrections to the cut ladder — objections recorded

- **Date:** 2026-08-09
- **Status:** Accepted
- **Phase:** 12 (pre-Sprint-1 amendment, item 1 — **partial dissent**)

**Context**

The pre-Sprint-1 review instructed that all five named cuts from `feasibility.md` be taken now
as baseline scope. Four of the five are sound and have been taken. **Two are defective, and
the defects are mine — they were introduced in the Phase 12 cut ladder and survived the Phase
13 review.** Recording them here rather than complying silently, per the review's own
instruction to push back with reasoning.

**The two defective cuts**

**Cut 3 — "reduce the simulator from 300 couriers to 100, saves 3 h."** Two things are wrong
with it.

*It saves almost nothing.* The simulator's cost is the road-graph walker, the response
calibration, the ground-truth sink and the profile machinery. The courier count is a
configuration value. Changing `300` to `100` saves approximately **zero hours**, not three. The
3 h figure was asserted without analysis.

*It breaks a headline NFR.* 300 couriers reporting every 3 s is exactly the 100 msg/s of
NFR-003 — the number was derived, not coincidental (FR-018's acceptance criteria say so). At
100 couriers the ingest rate falls to **33 msg/s**, and the write-amplification demonstration
that is challenge 3.4 loses its stated load. The cut would have quietly invalidated an NFR
while appearing to be a cosmetic reduction.

**Cut 5 — "fall back to a grid road graph instead of real OSM geometry, saves 3 h."** The
saving is real but the cut is premature. `S1-12` is *already* a 6 h timebox whose documented
fallback is a simplified grid graph. Taking the cut now means pre-emptively surrendering real
street geometry — visible in the demo, and part of FR-018's acceptance criteria — to save 3 h
that the timebox already protects. **A conditional fallback that has not yet been triggered
should not be converted into an unconditional cut.**

**Alternatives considered**

| Option | Pros | Cons |
|---|---|---|
| Take all five cuts as instructed | Simple compliance; ladder totals 15 h | Cut 3 saves nothing and breaks NFR-003; cut 5 surrenders demo fidelity for a saving the timebox already guarantees |
| **Take cuts 1, 2 and 4; correct cut 3; reject cut 5** | Ladder is honest. NFR-003 survives. Street geometry is kept unless the timebox actually fires | The ladder shrinks from a claimed 15 h to a real 9 h, which makes the scope problem look worse |
| Take cut 3 but raise report frequency to 1 Hz to hold 100 msg/s | Preserves NFR-003 at 100 couriers | Still saves ~0 h, and 1 Hz reporting from 100 couriers is a less realistic mobile profile than 0.33 Hz from 300 |

**Decision**

- **Cuts 1, 2 and 4 taken** as baseline scope: live map degraded to static with 5 s polling
  (−4 h); Grafana reduced to two panels (−2 h); hosted demo dropped (−3 h).
- **Cut 3 withdrawn.** The simulator stays at 300 couriers. It is struck from the cut ladder
  entirely rather than re-costed, because its real saving is ~0 h.
- **Cut 5 rejected as a pre-emptive cut**, retained as the existing conditional fallback inside
  `S1-12`'s 6 h timebox. If the timebox fires, the grid graph is used and the compromise is
  recorded — unchanged from the original plan.
- **The real cut ladder therefore totals ~9 h, not 15 h.** This is stated plainly.

**Reason for selection**

The constraint that decided it: **a cut ladder is a safety mechanism, and a safety mechanism
containing two items that do not work is worse than a shorter one that does** — because the
project would have drawn on it in week 11 under pressure, discovered cut 3 saved nothing, and
had less runway than it believed at the worst possible moment.

The consequence is uncomfortable and is the point: the honest ladder is 9 h, not 15 h. That
*strengthens* the review's item 2 finding rather than weakening it — the cut ladder was thin
because prioritisation had been deferred, and two of its entries were padding. The re-triage
in item 2 (Must ratio 96% → 78%) is what actually produces a usable ladder, now **≈38 h** from
genuinely demoted requirements.

**Consequences**

*Becomes easy:* the ladder can be trusted. Every remaining entry has a verified saving and a
stated loss.

*Becomes hard:* less apparent slack than the plan previously claimed. This is a disclosure,
not a regression — the slack was never there.

*Now expensive to reverse:* nothing is foreclosed. Cut 5 remains available the moment
`S1-12`'s timebox fires.

*Flagged to the reviewer:* this is a partial dissent from the pre-Sprint-1 review's item 1. The
review was right that all available cuts should be taken now rather than held to Sprint 4; it
was wrong to assume the ladder's five entries were each valid, because they inherited defects
from the plan being reviewed.
