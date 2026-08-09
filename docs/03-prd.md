# Product Requirements: Wassal

**Phase:** 4 · **Date:** 2026-08-08

> Requirement IDs are stable and never renumbered. A dropped requirement is marked
> `Withdrawn` and its ID retired.

---

## Executive Summary

Wassal is a real-time dispatch platform that matches delivery orders to couriers, built as a
portfolio artifact whose purpose is to demonstrate distributed-systems correctness with
measurable evidence rather than to serve users.

An order arrives; the system finds nearby available couriers, offers the job to the best
candidate with a bounded 15-second response window, and assigns on acceptance. Declines and
timeouts pass the offer onward. Cancellation after acceptance returns the order to the pool
and releases the courier through a compensating saga. Customers watch assigned couriers move
on a live map.

Load comes from a first-class simulator: 300 deterministic couriers moving on real Tunis road
geometry, with calibrated accept/decline/ignore behaviour and Poisson order arrivals.

**"Done" for v1 means:** six invariants (INV-1…INV-6) enforced, each with a named test and a
runtime Prometheus counter; four proof suites passing (concurrency, chaos, idempotency,
load); a reconciliation job reporting zero unexplained variance; and a README that leads with
the architecture diagram, the invariants and the measured numbers — including any target that
was missed. Feature thinness is deliberate (ADR-0001).

---

## Business Objectives

| # | Objective | Traces to |
|---|---|---|
| BO-1 | Produce reproducible evidence of concurrency correctness under contention | G-1, P-1 |
| BO-2 | Produce reproducible evidence of correct recovery from mid-operation failure | G-2, P-2 |
| BO-3 | Publish measured latency and throughput against stated targets, including misses | G-3, P-3/P-4 |
| BO-4 | Make the system runnable by a stranger in one command | G-4 |
| BO-5 | Close three named experience gaps — Kafka operations, distributed locking, WebSocket scaling — with artifacts demonstrating each | G-5 |
| BO-6 | Capture and publish at least one honest bug story from the proof suites | G-6 |

---

## Personas

| Persona | Role | Goals | Pain points | Technical comfort |
|---|---|---|---|---|
| **Reader** | Hiring engineer evaluating the repository | Decide in 5–10 minutes whether the author can build correct distributed systems | Portfolio repos that claim patterns without evidence; READMEs that open with setup instructions; unreproducible numbers | Very high — will read the ADRs and look for the atomic claim |
| **Author** | Developer, operator, sole maintainer | Close three experience gaps; finish something demonstrable | Solo motivation decay; nine containers to operate; the map tempting effort out of sequence | High, with three named gaps |
| **Merchant** (modelled) | Creates orders | Order accepted and assigned quickly; see courier position | Doesn't exist as a real user — the simulator and demo UI play this role | n/a |
| **Courier** (modelled) | Receives offers, delivers | Receive relevant nearby offers; never be double-booked | Unreliable mobile network causing duplicate submissions — modelled deliberately, since it motivates FR-014 | n/a |
| **Operator** (modelled) | Observes system health | See invariant violations, latency and queue depth at a glance | Silent violations | The Author, wearing a different hat |

---

## Functional Requirements

Priority: **Must** = v1 does not ship without it. Counts and the Must-ratio check are in
Prioritisation at the end.

---

### A. Order Lifecycle

**FR-001 — Create order**
- **Priority:** Must
- **Description:** Accept an order with pickup and dropoff coordinates, returning an order ID and `PENDING` status. Creation is idempotent on a client-supplied `Idempotency-Key`.
- **Rationale:** Entry point to the entire flow (BO-1).
- **Acceptance criteria:**
  - Given valid pickup and dropoff within the Tunis bounding box, when an order is created, then it is persisted with status `PENDING`, an `OrderCreated` event is written to the outbox **in the same transaction**, and `201` is returned with the order ID.
  - Given a repeated `Idempotency-Key` from a prior successful creation, when creation is retried, then the **original** order ID is returned with `200`, and no second order or second event exists.
  - Given coordinates outside the configured bounding box, when an order is created, then `422` is returned with `ORDER_OUT_OF_BOUNDS` and nothing is persisted.
- **Edge cases:** Identical key with *different* payload → `409 IDEMPOTENCY_KEY_REUSED`, original unchanged. Two concurrent requests with the same key → exactly one order, the loser reads the winner's result (unique constraint, not check-then-act). Pickup equal to dropoff → allowed, valid degenerate case.
- **Error scenarios:** Postgres unavailable → `503`, nothing persisted, no event. Outbox insert fails → whole transaction rolls back; an order without its event must never exist.
- **Dependencies:** FR-013 (outbox), FR-014 (idempotency).

**FR-002 — Order state machine**
- **Priority:** Must
- **Description:** An order occupies exactly one of `PENDING`, `OFFERING`, `ASSIGNED`, `PICKED_UP`, `DELIVERED`, `CANCELLED`, `UNASSIGNABLE`. All transitions are validated against an explicit table; invalid transitions are rejected without side effects.
- **Rationale:** INV-4 is unprovable without an explicit machine (BO-2).
- **Acceptance criteria:**
  - Given an order in any state, when an invalid transition is attempted, then it is rejected with `409 INVALID_STATE_TRANSITION`, no event is emitted, and no state changes.
  - Given any state transition succeeds, then exactly one domain event is written to the outbox in the same transaction.
  - Given an order reaches `DELIVERED`, `CANCELLED` or `UNASSIGNABLE`, then no further transition is permitted.
- **Edge cases:** Concurrent transitions from the same state → exactly one wins via conditional update on current state; the loser observes zero affected rows and returns `409`. Transition to the state already held → idempotent no-op, no duplicate event.
- **Error scenarios:** Crash between state write and outbox publish → impossible by construction (same transaction). Crash after commit, before Kafka publish → outbox publisher retries; at-least-once absorbed by FR-014.
- **Dependencies:** FR-013.

**FR-003 — Query order status**
- **Priority:** **Should** (demoted 2026-08-09, item 2 re-triage — the README's argument is
  about invariants; tests assert against the database directly, and the map is itself Should)
- **Description:** Return an order's current state, assigned courier if any, and last known courier position if assigned.
- **Rationale:** Demo surface and test observability (BO-4).
- **Acceptance criteria:**
  - Given an existing order, when queried, then current state and assignment are returned within 100 ms p95.
  - Given an assigned order, when queried, then the courier's last known position is included with its `recorded_at` timestamp.
  - Given an unknown order ID, then `404 ORDER_NOT_FOUND`.
- **Edge cases:** Assigned but no position yet reported → position `null`, not an error. Position older than 60 s → returned with a `stale: true` flag rather than suppressed, so the demo shows honest data.
- **Dependencies:** FR-010.

**FR-004 — Complete delivery**
- **Priority:** Must
- **Description:** A courier marks an assigned order `PICKED_UP`, then `DELIVERED`. Completion releases the courier to the available pool exactly once.
- **Acceptance criteria:**
  - Given an `ASSIGNED` order, when its assigned courier reports pickup, then state becomes `PICKED_UP` and `OrderPickedUp` is emitted.
  - Given a `PICKED_UP` order, when its assigned courier reports delivery, then state becomes `DELIVERED`, the assignment closes, and the courier returns to `AVAILABLE` **exactly once** (INV-6).
  - Given a courier who is **not** the assignee reports completion, then `403 NOT_ASSIGNED_COURIER` and no state changes.
- **Edge cases:** Delivery reported twice → second is an idempotent no-op; courier is not released twice. Delivery reported while `ASSIGNED` (pickup skipped) → rejected `409`; the machine has no shortcut. Delivery arriving after a cancellation already released the courier → rejected `409`, courier not re-released.
- **Dependencies:** FR-002, FR-008, FR-014.

---

### B. Courier and Availability

**FR-005 — Courier availability toggle**
- **Priority:** Must
- **Description:** A courier transitions between `OFFLINE`, `AVAILABLE` and `BUSY`. Going `AVAILABLE` adds them to the geospatial index; leaving removes them.
- **Acceptance criteria:**
  - Given an `OFFLINE` courier with a known position, when they go available, then status becomes `AVAILABLE` and they appear in the geo index within 1 s.
  - Given an `AVAILABLE` courier, when they go offline, then they are removed from the geo index and receive no further offers.
  - Given a `BUSY` courier (holding an active assignment), when they attempt to go available, then `409 COURIER_HAS_ACTIVE_ASSIGNMENT` — protecting INV-1 at the API boundary as well as at the claim.
- **Edge cases:** Going available with no position ever reported → `422 POSITION_REQUIRED`; an unlocatable courier in the geo index is a correctness hazard. Going offline while holding a live offer → offer is cancelled and re-dispatched immediately rather than waiting for expiry. Going offline mid-assignment → allowed, treated as cancellation (FR-009).
- **Dependencies:** FR-006, FR-010.

**FR-006 — Geospatial candidate search**
- **Priority:** Must
- **Description:** Given a pickup point, return the nearest N available couriers within radius R, ordered by distance, excluding any courier already offered this order or holding an active assignment.
- **Rationale:** The latency-critical step inside the 500 ms budget (BO-3).
- **Acceptance criteria:**
  - Given 300 available couriers, when a search runs for N=5 within R=3 km, then results return in **< 50 ms p99**, ordered by ascending distance.
  - Given no couriers within R, when searched, then an empty list is returned — not an error — and the order transitions toward `UNASSIGNABLE` per FR-007.
  - Given a courier previously offered this same order, then they are excluded from subsequent candidate sets for it.
- **Edge cases:** All candidates exhausted → `UNASSIGNABLE` (INV-4 terminal state, never a hang). Courier goes offline between search and offer → offer creation fails the conditional claim and the next candidate is taken. Ties at identical distance → broken deterministically by courier ID, so seeded runs are reproducible.
- **Dependencies:** FR-005, FR-010.

---

### C. Offer and Assignment — the core

**FR-007 — Offer lifecycle**
- **Priority:** Must
- **Description:** An offer is created for one candidate with a persisted deadline (default 15 s). It terminates as `ACCEPTED`, `DECLINED` or `EXPIRED`. On decline or expiry the next candidate is offered; when candidates are exhausted the order becomes `UNASSIGNABLE`.
- **Acceptance criteria:**
  - Given a `PENDING` order with candidates, when dispatch runs, then an offer is created for the nearest candidate with `expires_at = now + 15 s` **persisted in the database**, the order moves to `OFFERING`, and the offer is delivered to the courier.
  - Given an outstanding offer, when the courier declines, then the offer becomes `DECLINED` and the next candidate is offered within 200 ms.
  - Given an offer whose deadline passes with no response, then it becomes `EXPIRED` **within ±1 s of the deadline**, and the next candidate is offered.
  - Given the candidate list is exhausted, then the order becomes `UNASSIGNABLE` and emits `OrderUnassignable`.
- **Edge cases:** **Accept arriving after the deadline** → rejected; expiry is authoritative (A-07). **Accept and expiry racing at the same instant** → both attempt one conditional transition on `offer.status`; exactly one affects a row, the loser no-ops (FR-012). Service restart spanning a deadline → sweeper fires on recovery, still within tolerance (FR-011). Courier goes offline while holding the offer → offer cancelled immediately, next candidate taken.
- **Error scenarios:** Redis unavailable at offer time → offer creation fails, order stays `PENDING`, retried by the sweeper. Never a silent drop.
- **Dependencies:** FR-006, FR-008, FR-011, FR-012.

**FR-008 — Atomic courier claim**
- **Priority:** Must
- **Description:** Acceptance atomically claims the courier and the order together. The claim is a single conditional operation; a check-then-act sequence is explicitly forbidden.
- **Rationale:** The headline requirement. INV-1, INV-2 and BO-1 rest entirely on it.
- **Acceptance criteria:**
  - Given two orders accepted simultaneously by/for the same courier, when both claims execute, then **exactly one succeeds**; the loser observes zero affected rows and receives `409 COURIER_UNAVAILABLE`.
  - Given a successful claim, then the assignment is persisted, courier becomes `BUSY`, order becomes `ASSIGNED`, the courier is removed from the geo index, and `AssignmentCreated` is emitted — all in one transaction.
  - Given **5 000 concurrent acceptance attempts** against a pool of 50 couriers, then assignments created ≤ 50, invariant violations = 0, and every rejected attempt received a definite error rather than a timeout.
- **Edge cases:** Same courier accepting two offers in the same millisecond → one wins (INV-1). Two couriers accepting the same order → one wins (INV-2). The same accept delivered N times → exactly one assignment (INV-3, via FR-014). Claim succeeds but geo-index removal fails → assignment stands; the index is reconciled by the sweeper, because Postgres is authoritative and Redis is a cache.
- **Error scenarios:** Postgres unavailable → claim fails closed, offer remains outstanding, expiry still applies. **Never fail open.**
- **Dependencies:** FR-005, FR-007, FR-014.

**FR-009 — Cancellation and compensating saga**
- **Priority:** Must
- **Description:** Cancellation after acceptance runs a compensating saga: return the order to the pool, release the courier, notify subscribed customers, recompute ETA. Every compensating step is idempotent and safe to retry.
- **Acceptance criteria:**
  - Given an `ASSIGNED` order, when the courier cancels, then the order returns to `PENDING`, the courier returns to `AVAILABLE` and re-enters the geo index **exactly once** (INV-6), subscribers are notified, and re-dispatch begins within 1 s.
  - Given any compensating step is executed twice, then the end state is identical to executing it once.
  - Given a compensating step fails, then it is retried with backoff until success or until the saga is marked `FAILED_NEEDS_ATTENTION` and counted in a Prometheus metric — never silently abandoned.
  - Given the order was already `DELIVERED`, when cancellation arrives, then `409 ORDER_TERMINAL` and no compensation runs.
- **Edge cases:** Cancel arriving during `PICKED_UP` → permitted; the goods-in-transit problem is out of scope, documented as a simplification. Two cancellations racing → one saga instance, enforced by a unique key on `(order_id, saga_type, trigger_event_id)`. Service dies mid-saga → resumed from the persisted saga state on restart, not restarted from the beginning.
- **Dependencies:** FR-002, FR-005, FR-008, FR-013, FR-014.

---

### D. Location and Real-Time

**FR-010 — Location ingest with hot/cold split**
- **Priority:** Must
- **Description:** Couriers report position every few seconds. Writes take two paths: a hot path (last-known position, overwritten, read-optimised) and a cold path (append-only history, batched). The two are decoupled; the hot path never blocks on the cold path.
- **Rationale:** BO-3. 100 msg/s written naively to Postgres is the stated failure mode.
- **Acceptance criteria:**
  - Given 100 location messages/second sustained, when ingested, then hot-path writes complete at **p99 < 20 ms** and the **Postgres write rate is at least 10× lower** than the ingest rate.
  - Given a position report, when written to the hot path, then it is readable by candidate search and by FR-003 within 100 ms.
  - Given the batching window elapses or the buffer fills, then buffered positions are flushed to `location_history` in one batched write.
  - Given a crash with a partially filled buffer, then **at most one flush window of history is lost**, the hot path is unaffected, and the loss is counted in a metric — the loss window is documented, not hidden.
- **Edge cases:** Out-of-order reports (mobile networks reorder) → hot path keeps the highest `recorded_at`, never a stale overwrite. Duplicate report → idempotent, one history row via `(courier_id, recorded_at)` uniqueness. Report from an unknown courier → rejected `404`, counted; not silently accepted. Position outside the bounding box → accepted but flagged, since rejecting real GPS drift would be wrong.
- **Error scenarios:** Redis down → ingest returns `503`; positions are dropped by design and counted. Location is a cache, not a ledger — stated explicitly so the tradeoff is visible.
- **Dependencies:** FR-005.

**FR-011 — Durable offer expiry**
- **Priority:** Must
- **Description:** Offer deadlines are persisted, never held in an in-process timer. A sweeper claims and expires overdue offers. Expiry survives process death.
- **Rationale:** P-4 in full. BO-2.
- **Acceptance criteria:**
  - Given an outstanding offer, when its deadline passes, then it expires **within ±1 s** of the deadline.
  - Given `dispatch-service` is killed at t=7 s of a 15 s offer and restarted at t=12 s, then the offer still expires within ±1 s of t=15 s, and the order is re-dispatched.
  - Given `dispatch-service` is killed at t=7 s and restarted at t=20 s, then the offer is expired **immediately on recovery**, and the total order delay is bounded and observable.
  - Given multiple sweeper instances, then each overdue offer is expired **exactly once** — enforced by a conditional claim, not by leader election.
- **Edge cases:** Clock skew between instances → deadlines are compared against database time, never instance wall-clock. Sweeper falls behind → lag is a Prometheus gauge and an alert condition; silent lag is the failure mode this metric exists to prevent. Deadline passes while the accept is in flight → FR-012 governs.
- **Dependencies:** FR-007, FR-012.

**FR-012 — Deterministic accept-vs-expire resolution**
- **Priority:** Must
- **Description:** Accept and expiry both funnel through a **single conditional state transition** on `offer.status`. Whichever commits first wins; the loser observes zero affected rows and takes a defined no-op branch. Expiry is authoritative: an accept arriving after the deadline is rejected even if it reaches the database first (A-07).
- **Rationale:** The subtlest correctness requirement in the system. BO-1, BO-2.
- **Acceptance criteria:**
  - Given an accept at t=14.98 s and the sweeper firing at t=15.00 s, then exactly one outcome is committed, the other is a no-op, and the courier is left in a consistent state either way.
  - Given the accept commits first, then the offer is `ACCEPTED`, the assignment exists, and the sweeper's later attempt affects zero rows and emits nothing.
  - Given expiry commits first, then the offer is `EXPIRED`, the accept returns `410 OFFER_EXPIRED`, **no assignment is created**, and the courier is not marked busy.
  - Given an accept arriving after the persisted deadline even with the offer still `OFFERED`, then it is rejected — the deadline predicate is part of the conditional, not merely the sweeper's trigger.
- **Edge cases:** Accept and expiry in the same transaction window → serialised by the row lock; no distributed coordination needed. Accept retried after a rejected expiry race → still rejected, idempotently, with the same error.
- **Dependencies:** FR-007, FR-008, FR-011.

**FR-016 — Live position streaming over WebSocket**
- **Priority:** Must
- **Description:** Clients subscribe to an order and receive courier position updates. Subscriptions survive gateway instance boundaries: a socket on instance A receives updates produced on instance B, via Redis Pub/Sub fan-out.
- **Rationale:** Challenge 3.5; BO-5 (WebSocket scaling gap).
- **Acceptance criteria:**
  - Given a client subscribed to an assigned order, when its courier reports a position, then the update reaches the client **within 1 s** of ingest.
  - Given the subscriber's socket is on gateway instance A and ingest occurs on instance B, then the update is still delivered — proven by a test running two gateway instances.
  - Given **500 concurrent subscribed connections**, then the 1 s delivery target holds at p95 and no connection is dropped for backpressure.
  - Given a client reconnects after a disconnection, then it receives the **current** position immediately on resubscribe, and missed intermediate positions are **not** replayed.
- **Edge cases:** Subscribing to an unassigned order → accepted; updates begin when assignment occurs. Subscribing to a terminal order → immediate final state, then server-initiated close. Courier reports nothing for 60 s → client receives a staleness marker rather than silence. Slow consumer → per-connection bounded queue, oldest dropped first, drops counted.
- **Error scenarios:** Redis Pub/Sub unavailable → sockets stay open, updates stop, clients receive a degraded-mode marker. Silent stalling is forbidden.
- **Dependencies:** FR-010.

The reconnection semantics deserve a note, since they are a deliberate design choice rather
than a simplification: **positions are current-state, not an event stream.** Replaying missed
positions would show a courier retracing a path they have already travelled. Latest-wins is
both cheaper and more correct here.

---

### E. Correctness Infrastructure

**FR-013 — Transactional outbox**
- **Priority:** Must
- **Description:** Domain events are written to an outbox table in the same transaction as the state change, then published to Kafka by a separate publisher. No event is emitted for an uncommitted state change, and no state change commits without its event.
- **Rationale:** INV-5. The DB→Kafka atomicity boundary.
- **Acceptance criteria:**
  - Given any state transition, then its event row is inserted in the same transaction; rolling back the transaction leaves no event.
  - Given the publisher is stopped for 60 s and restarted, then all pending events publish in per-aggregate order with no loss.
  - Given the publisher crashes after Kafka accepts but before marking the row sent, then the event republishes on recovery and consumers deduplicate (FR-014) — at-least-once publication with effectively-once effect.
  - Given normal operation, then publish lag is **p99 < 200 ms** and exported as a Prometheus gauge.
- **Edge cases:** Outbox grows unbounded if Kafka is down → depth is a gauge with an alert; sent rows are pruned on a schedule. Ordering within an aggregate is preserved; ordering across aggregates is explicitly **not** guaranteed, and consumers must not assume it.
- **Dependencies:** FR-002.

**FR-014 — Consumer-side idempotency and dedup**
- **Priority:** Must
- **Description:** Every consumer and every mutating command deduplicates on an idempotency key, converting Kafka's at-least-once delivery into effectively-once business effect. Dedup state has a defined location and expiry.
- **Rationale:** INV-3, INV-5, P-3.
- **Acceptance criteria:**
  - Given the same message delivered N times, then the business effect occurs exactly once and duplicates 2…N are counted in a `duplicate_suppressed_total` metric.
  - Given an accept command replayed **1 000 times**, then exactly one assignment exists (INV-3).
  - Given dedup state older than its retention window, then it is pruned, and the window is **strictly greater than** the maximum Kafka retention plus maximum retry duration — so a replay can never outlive its dedup record.
  - Given the dedup record exists but the effect was not committed, then the record and the effect share a transaction, making that state impossible.
- **Edge cases:** Two duplicates processed concurrently → unique constraint decides; one commits, one catches the violation and no-ops. Dedup table growth → pruned on schedule, size exported as a gauge.
- **Dependencies:** FR-013.

**FR-015 — Invariant counters**
- **Priority:** Must
- **Description:** Each of INV-1…INV-6 has a runtime check with a Prometheus counter incremented on violation. Violations are observable in production, not merely caught in tests.
- **Acceptance criteria:**
  - Given any invariant violation at runtime, then `wassal_invariant_violation_total{invariant="INV-n"}` increments and the event is logged at ERROR with correlation ID.
  - Given a **deliberately injected** violation in a test, then the corresponding counter increments — every counter must be seen non-zero at least once in the test suite.
  - Given a Grafana dashboard, then all six counters appear on one panel, since a violation must be visible without a query being written.
- **Edge cases:** A counter never seen non-zero is untested code — the deliberate-injection test exists precisely to prevent that (risk R-8).
- **Dependencies:** FR-002, FR-008, FR-009, FR-013.

**FR-017 — Reconciliation job**
- **Priority:** **Should** (demoted 2026-08-09 — the three-source job is stretch scope under
  ADR-0010. **FR-020 ground-truth emission stays Must and carries the non-circularity claim**
  at reduced strength if this does not land)
- **Description:** An independent job compares live state against the event log and reports variance. It shares **no domain code** with the services and reads only raw tables and the raw event log via its own queries.
- **Rationale:** BO-1, BO-2, and risk R-9 — a self-referential proof proves nothing.
- **Acceptance criteria:**
  - Given a completed simulator run, when reconciliation runs, then it reports **zero unexplained variance** between the event log and the live tables.
  - Given a deliberately corrupted row, then reconciliation detects and reports it — proving the job can fail.
  - Given the job's source, then it imports no service domain package; this is enforced by an ArchUnit rule, not by convention.
- **Edge cases:** Run while the system is live → variance from in-flight operations is expected; the job takes a consistent snapshot and reports in-flight items separately from genuine variance.
- **Dependencies:** FR-013.

---

### F. Simulator

**FR-018 — Deterministic courier simulator**
- **Priority:** Must
- **Description:** 300 configurable simulated couriers move on a precomputed Tunis road graph at 15–40 km/h with realistic stops and idle periods, driven by a seeded RNG so any run is exactly reproducible.
- **Acceptance criteria:**
  - Given the same seed and configuration, when run twice, then both runs produce **byte-identical** courier trajectories and order arrival timings.
  - Given the simulator runs, then couriers follow road-graph edges — never straight lines between points.
  - Given 300 couriers reporting every 3 s, then sustained ingest is ≈100 msg/s (the FR-010 target, derived rather than coincidental).
- **Edge cases:** Courier reaching a graph dead-end → reverses. Courier assigned an order → routes toward pickup rather than continuing the random walk. Configured count above graph capacity → warns rather than clustering unrealistically.
- **Dependencies:** FR-005, FR-010, and the offline road-graph asset (A-01).

**FR-019 — Calibrated response and arrival behaviour**
- **Priority:** **Should** (demoted 2026-08-09 — a fixed accept/ignore split folded into
  FR-018 is enough to exercise expiry continuously. Statistical calibration to ±3 pp is
  realism, not proof)
- **Description:** Couriers respond to offers with calibrated probabilities: **60% accept, 25% ignore until expiry, 15% explicit decline**, with ~5% post-acceptance cancellation. Orders arrive as a Poisson process with a configurable evening peak at 2–3× baseline.
- **Acceptance criteria:**
  - Given ≥ 1 000 offers, then observed accept/ignore/decline rates fall within ±3 percentage points of configuration.
  - Given the peak window, then arrival rate reaches 2–3× baseline and the system's behaviour under peak is measured.
  - Given the ignore behaviour, then those offers reach expiry naturally — exercising FR-011 continuously rather than only in dedicated tests.
- **Edge cases:** Very high decline rates → orders reach `UNASSIGNABLE`, which is correct and must be counted rather than treated as an error.
- **Dependencies:** FR-018.

**FR-020 — Ground-truth emission**
- **Priority:** Must
- **Description:** The simulator emits what it *intended* to happen — offers it accepted, positions it visited, orders it created — to an independent sink, so system behaviour is validated against an external record rather than against itself.
- **Rationale:** Directly mitigates R-9. The difference between a proof and a tautology.
- **Acceptance criteria:**
  - Given a run, then ground truth is written to a sink not shared with service state.
  - Given a completed run, then every accepted offer in ground truth has exactly one corresponding assignment in system state, and vice versa — with **no unmatched records in either direction**.
  - Given a deliberately introduced system fault, then the ground-truth comparison detects the divergence.
- **Dependencies:** FR-018, FR-019.

**FR-021 — Stress profile**
- **Priority:** Must
- **Description:** A configuration profile that deliberately maximises contention: many orders converging on few couriers in a small radius.
- **Rationale:** The profile that proves INV-1 and INV-2. Natural traffic almost never produces the race.
- **Acceptance criteria:**
  - Given the stress profile, then ≥ 10 orders per available courier are generated within a radius smaller than the standard search radius.
  - Given a stress run, then contention is confirmed observed — the count of failed atomic claims is > 0, proving the race actually occurred rather than being absent.
  - Given a stress run, then invariant violations = 0.
- **Edge cases:** If failed claims are **zero**, the test is invalid and must fail loudly — an untriggered race is not a passed test. This is the single most important edge case in the PRD.
- **Dependencies:** FR-018, FR-008.

---

### G. Demonstration Surface

**FR-022 — Live map**
- **Priority:** Should
- **Description:** A single-screen React + MapLibre view showing couriers, orders and live movement over OpenStreetMap tiles.
- **Rationale:** Makes the system legible in a reader's five-minute window (R-10).
- **Acceptance criteria:**
  - Given the map is open, then available couriers, busy couriers and pending orders are visually distinguishable.
  - Given a courier moves, then the marker updates within 1 s via the FR-016 WebSocket stream — not by polling.
  - Given 300 couriers, then the map sustains ≥ 30 fps on a mid-range laptop.
- **Edge cases:** WebSocket drops → visible reconnecting indicator, never a silently frozen map. Tile server unreachable → markers still render on a blank background.
- **Priority note:** **Should**, not Must, and deliberately so. Under scope pressure this is the third cut (feasibility ladder). The system's value does not depend on it — the ordering constraint (C-8) exists to enforce that.
- **Dependencies:** FR-016.

**FR-023 — Operator dashboards**
- **Priority:** **Should** (demoted 2026-08-09 — **FR-015's counters stay Must**. Grafana
  renders them; `/actuator/prometheus` plus a screenshot proves them. Reduced to two panels as
  baseline scope per ADR-0011)
- **Description:** Three Grafana panels: assignment latency histogram, offer outcome breakdown (accept/decline/expire), and invariant violation counters.
- **Acceptance criteria:**
  - Given the stack is running, then all three panels populate without manual configuration — dashboards are provisioned as code.
  - Given an invariant violation, then it is visible on the dashboard within 30 s.
- **Priority note:** Must, unlike the map, because these panels are the *evidence surface* and appear in the README. Panels beyond these three are the second cut.
- **Dependencies:** FR-015.

---

## Invariants

First-class requirements, not properties hoped for. Each has a formal statement, the
requirements that enforce it, a named test, and a runtime Prometheus counter (FR-015). An
invariant with a test but no counter is only proven in the lab; one with a counter but no
test is only hoped for. Both are required.

**INV-1 — A courier has at most one active assignment at any instant**
- **Formally:** `∀ courier c: |{a ∈ assignments : a.courier_id = c ∧ a.status = ACTIVE}| ≤ 1`
- **Enforced by:** FR-008 (atomic claim), FR-005 (availability guard), FR-009 (release on cancel)
- **Acceptance:** Under FR-021's stress profile, 5 000 concurrent acceptance attempts against 50 couriers produce ≤ 50 assignments, zero couriers with two.
- **Named test:** `Inv1DoubleAssignmentTest` — concurrency suite
- **Counter:** `wassal_invariant_violation_total{invariant="INV-1"}`
- **Detected at runtime by:** a partial unique index on `assignments(courier_id) WHERE status = 'ACTIVE'`. The database refuses the violation; the counter records the attempt.

**INV-2 — An order has at most one active assignment at any instant**
- **Formally:** `∀ order o: |{a ∈ assignments : a.order_id = o ∧ a.status = ACTIVE}| ≤ 1`
- **Enforced by:** FR-008, FR-002 (state machine)
- **Acceptance:** Two couriers accepting the same order concurrently → exactly one assignment, the loser receives `409`.
- **Named test:** `Inv2SingleAssignmentPerOrderTest`
- **Counter:** `…{invariant="INV-2"}`
- **Detected at runtime by:** partial unique index on `assignments(order_id) WHERE status = 'ACTIVE'`

**INV-3 — An accepted offer produces exactly one assignment, however many times the accept is delivered**
- **Enforced by:** FR-008, FR-012 (single conditional transition), FR-014 (dedup)
- **Acceptance:** The same accept command replayed 1 000 times produces exactly one assignment and 999 `duplicate_suppressed_total` increments.
- **Named test:** `Inv3AcceptIdempotencyTest` — idempotency suite
- **Counter:** `…{invariant="INV-3"}`
- **Note:** This is the invariant most likely to pass in testing and fail in reality, because duplicates arrive concurrently rather than sequentially. The test must replay in parallel, not in a loop.

**INV-4 — Every order reaches a terminal state; none sits in an intermediate state beyond its SLA, including across restarts**
- **Terminal states:** `DELIVERED`, `CANCELLED`, `UNASSIGNABLE`
- **Enforced by:** FR-011 (durable expiry), FR-007 (candidate exhaustion → `UNASSIGNABLE`), FR-002
- **Acceptance:** After a simulator run including mid-run service kills, zero orders remain non-terminal past their SLA. A dedicated sweeper query reports stuck orders as a gauge.
- **Named test:** `Inv4NoStuckOrdersTest` — chaos suite
- **Counter:** `…{invariant="INV-4"}` plus gauge `wassal_orders_stuck_total`
- **Note:** the only invariant requiring a *time-bounded* observation rather than a point-in-time check — which is why it needs a gauge as well as a counter.

**INV-5 — Every state transition emits its domain event exactly once after consumer-side dedup**
- **Enforced by:** FR-013 (outbox), FR-014 (inbox dedup)
- **Acceptance:** Reconciliation (FR-017) finds a one-to-one correspondence between state transitions and post-dedup events, in both directions, with zero unmatched records.
- **Named test:** `Inv5EventExactlyOnceTest` + the reconciliation job
- **Counter:** `…{invariant="INV-5"}`

**INV-6 — A courier released by cancellation or expiry returns to the available pool exactly once**
- **Enforced by:** FR-009 (idempotent compensation), FR-004 (completion release), FR-011
- **Acceptance:** A cancellation compensation replayed 100 times results in one release, one geo-index insertion, and the courier appearing exactly once in candidate search results.
- **Named test:** `Inv6SingleReleaseTest`
- **Counter:** `…{invariant="INV-6"}`
- **Note:** the double-release hazard is subtle — a courier released twice can appear twice in a candidate list and be offered the same order twice, which then threatens INV-1. INV-6 is load-bearing for INV-1, not merely tidy.

### Traceability matrix

| Invariant | Enforcing FRs | Test | Counter | Structurally guaranteed by |
|---|---|---|---|---|
| INV-1 | FR-005, FR-008, FR-009 | `Inv1DoubleAssignmentTest` | ✓ | Partial unique index |
| INV-2 | FR-002, FR-008 | `Inv2SingleAssignmentPerOrderTest` | ✓ | Partial unique index |
| INV-3 | FR-008, FR-012, FR-014 | `Inv3AcceptIdempotencyTest` | ✓ | Unique idempotency key |
| INV-4 | FR-002, FR-007, FR-011 | `Inv4NoStuckOrdersTest` | ✓ + gauge | Sweeper + terminal-state rule |
| INV-5 | FR-013, FR-014 | `Inv5EventExactlyOnceTest`, FR-017 | ✓ | Same-transaction outbox write |
| INV-6 | FR-004, FR-009, FR-011 | `Inv6SingleReleaseTest` | ✓ | Conditional release transition |

The right-hand column matters more than the others. **Where an invariant is guaranteed by a
database constraint rather than by application logic, it cannot be violated by a bug in the
service** — the write simply fails. Four of six are structurally guaranteed. INV-4 and INV-5
are not, because they are properties of a process over time rather than of a row, which is
exactly why they carry the heaviest test and reconciliation burden.

---

## Non-Functional Requirements

**NFR-001 — Assignment latency**
- **Category:** Performance
- **Requirement:** p99 < 500 ms from order creation to first offer delivered, at 50 orders/min with 300 active couriers.
- **Measurement:** k6 or Gatling scenario against the standard profile; histogram from Prometheus; published in the README with the hardware it was measured on.
- **Priority:** Must

**NFR-002 — Candidate search latency**
- **Category:** Performance
- **Requirement:** p99 < 50 ms for nearest-5-within-3 km across 300 available couriers.
- **Measurement:** Micro-benchmark plus a production histogram; a sub-budget of NFR-001.
- **Priority:** Must

**NFR-003 — Location ingest throughput and write amplification**
- **Category:** Performance
- **Requirement:** 100 msg/s sustained; Postgres write rate ≥ 10× lower than ingest rate; hot-path write p99 < 20 ms.
- **Measurement:** Prometheus rate counters on both paths, compared directly.
- **Priority:** Must
- **CLARIFIED AND MEASURED 2026-08-09 (Sprint 4).** As originally written, "Postgres write rate ≥ 10× lower than ingest" is ambiguous, and the two readings give opposite answers:

  | Reading | Measured | Verdict |
  |---|---|---|
  | **Rows** written to Postgres | 8,400 rows for 8,400 positions — **1:1** | **Not met, and cannot be** |
  | **Write statements** (transactions) | **17 batches** for 8,400 positions — **494× reduction** | **Met, by a wide margin** |

  The row reading was never achievable and should not have been written that way: nothing is
  discarded, so every position necessarily reaches the cold path eventually. What batching
  reduces is the number of write *operations*, and that is the quantity the write-amplification
  challenge (3.4) is actually about — index maintenance, WAL, and fsync are per-statement costs.

  The NFR is therefore restated as **write statements**, measured by
  `wassal_coldpath_flush_operations_total`, and the original ambiguous wording is kept above so
  the change is visible. Picking the flattering reading of an ambiguous requirement without
  saying so is the self-deception this project treats as its second adversary.

**NFR-004 — Offer expiry accuracy**
- **Category:** Reliability
- **Requirement:** Expiry fires within ±1 s of the persisted deadline, including across a service restart spanning the deadline.
- **Measurement:** Chaos suite measuring actual-versus-intended expiry time distribution.
- **Priority:** Must

**NFR-005 — Recovery time**
- **Category:** Reliability
- **Requirement:** After killing `dispatch-service` mid-assignment, the system returns to a consistent state within 30 s, with zero lost orders and zero invariant violations.
- **Measurement:** Chaos suite: kill, wait, run reconciliation, assert.
- **Priority:** Must

**NFR-006 — WebSocket capacity and latency**
- **Category:** Performance
- **Requirement:** 500 concurrent connections; position reaching a subscribed client within 1 s of ingest at p95.
- **Measurement:** Load harness with instrumented clients; timestamps compared at ingest and receipt.
- **Priority:** Must

**NFR-007 — Cold start**
- **Category:** Usability *(of the repository — the Reader is the user)*
- **Requirement:** Cold clone to fully running stack in one command, under 2 minutes, no manual steps.
- **Measurement:** CI job that clones fresh and runs `docker compose up`, asserting all health checks pass within the window.
- **Priority:** Must

**NFR-008 — Determinism**
- **Category:** Maintainability
- **Requirement:** Identical seed and configuration produce identical simulator output; every published number is reproducible by a stated command.
- **Measurement:** Two runs, byte-compared.
- **Priority:** Must

**NFR-009 — Test isolation**
- **Category:** Maintainability
- **Requirement:** Integration tests run against real Postgres, Redis and Redpanda via Testcontainers — no mocks for infrastructure. Chaos tests use Toxiproxy for network faults.
- **Measurement:** No infrastructure mock exists in the test tree; enforced by an ArchUnit rule.
- **Priority:** Must

**NFR-010 — Traceability**
- **Category:** Maintainability
- **Requirement:** Every request and every event carries a correlation ID propagated across service and Kafka boundaries; OpenTelemetry traces span the full assignment path.
- **Measurement:** A trace of one assignment shows all participating services in one view.
- **Priority:** Must

**NFR-011 — Resource footprint** · **PROVISIONAL — to be measured in Sprint 1 (`S1-14`)**
- **Category:** Portability
- **Requirement:** Full stack runs in **<= 6 GB** RAM on a developer laptop (Must); `core` Compose profile runs in **<= 5 GB** (Should).
- **Amended by review R-04.** The `core` sub-target was originally <= 3 GB. Measured budget after the Phase 6 two-gateway change (F-7) is ~4.7 GB, so 3 GB was already known to be unmeetable — and **a requirement known to be unmeetable is worse than none, because it trains a reader to discount the others.** The original figure and the reason it moved are recorded here rather than silently overwritten. A `minimal` profile (no simulator, one gateway) reaches ~3.2 GB if the lower figure is ever needed.
- **Measurement:** `docker stats` under the standard load profile.
- **Priority:** Should
- **Environment assumption, stated explicitly (item 5):** these figures are for **native Docker on Fedora Linux** — no Docker Desktop VM, no `hyperkit`/WSL2 memory reservation between the containers and the kernel. They assume a **16 GB host** running an IDE alongside. If the original figures were derived with VM-backed Docker overhead in mind, they are measuring something that does not exist on this machine.
- **Status: provisional.** Rather than relaxing the numbers on an assumption, `S1-14` measures the actual `core` and full-stack footprint once `S1-02` brings the profiles up, and the targets are **restated against the measurement**. The nine-container residual risk is re-rated at the same time.
- **FIRST MEASUREMENT, 2026-08-09 (`S1-15`, infrastructure tier only).** Native Docker on Fedora 44, SELinux Enforcing, 15.4 GB host:

  | Container | Limit | **Measured (idle)** |
  |---|---:|---:|
  | postgres | 768 MB | **41 MiB** |
  | redis | 256 MB | **4 MiB** |
  | redpanda | 1 024 MB | **213 MiB** |
  | **infra total** | **2 048 MB** | **≈258 MiB** |

  The limits are roughly **8× actual idle usage**.

  **SECOND MEASUREMENT, 2026-08-09 — full Sprint-1 stack running, six containers:**

  | Container | Limit | Measured |
  |---|---:|---:|
  | order-service | 384 MB | 190 MiB |
  | dispatch-service | 512 MB | 190 MiB |
  | gateway | 384 MB | 144 MiB |
  | postgres | 768 MB | 91 MiB |
  | redis | 256 MB | 4 MiB |
  | redpanda | 1 024 MB | 215 MiB |
  | **total** | **3 328 MB of limits** | **≈835 MiB actual** |

  The JVM tier is now included and the picture holds: **835 MiB against 3.3 GB of limits.**
  Extrapolating the three remaining services at ~190 MiB each plus the observability tier puts
  the full stack near **1.9 GB actual** — comfortably inside the 6 GB Must and, notably, inside
  the original 3 GB `core` sub-target that review R-04 recorded as missed.

  **The targets are still not being relaxed.** The limits are what protect NFR-011 under load,
  and this measurement is at low load with no simulator running. NFR-011 is restated once the
  simulator drives 100 msg/s in Sprint 4 — the point at which the numbers become adversarial
  rather than merely reassuring.
- **The 15%-of-hours abort signal is unaffected** by whatever the measurement shows — it concerns operational time, not memory, and that reasoning is independent.
- **General principle worth recording:** a target derived from an assumption about the environment is provisional until the environment is measured. Marking it so is cheaper than defending a number nobody checked.

**NFR-012 — Fail closed**
- **Category:** Security *(correctness-security, not confidentiality)*
- **Requirement:** Every claim, assignment and state transition fails closed on infrastructure unavailability. No code path may grant an assignment when its correctness precondition cannot be verified.
- **Measurement:** Chaos suite partitions Postgres and Redis in turn and asserts zero assignments created during the partition.
- **Priority:** Must

---

## User Stories

| ID | As a… | I want… | So that… | FRs | Priority | Est. |
|---|---|---|---|---|---|---|
| US-01 | Merchant | to create an order and get an ID back | I can track it | FR-001, FR-003 | Must | S |
| US-02 | Merchant | retries of my submission not to create duplicates | a flaky network doesn't cost me | FR-001, FR-014 | Must | S |
| US-03 | Courier | to go available and start receiving offers | I can work | FR-005 | Must | S |
| US-04 | Courier | to receive only offers near me | they are worth taking | FR-006 | Must | M |
| US-05 | Courier | never to be given two jobs at once | I can actually deliver both | FR-008 | Must | **L** |
| US-06 | Courier | an offer to expire if I don't answer | I'm not blamed for a stale job | FR-007, FR-011 | Must | **L** |
| US-07 | Courier | my accept to be honoured exactly once however many times it's sent | a retry doesn't double-book me | FR-008, FR-014 | Must | M |
| US-08 | Courier | to cancel and be released cleanly | I can go offline safely | FR-009 | Must | **L** |
| US-09 | Customer | to watch my courier move live | I know when to expect delivery | FR-016, FR-022 | Should | M |
| US-10 | Customer | the map to keep working if I reconnect | a tunnel doesn't break the page | FR-016 | Must | M |
| US-11 | Operator | to see invariant violations immediately | I know the system is honest | FR-015, FR-023 | Must | M |
| US-12 | Operator | independent reconciliation of state against the log | I can trust the numbers | FR-017 | Must | M |
| US-13 | Author | a deterministic simulator | any result is reproducible | FR-018, FR-019 | Must | **XL** |
| US-14 | Author | a stress profile that forces contention | INV-1 and INV-2 are actually tested | FR-021 | Must | M |
| US-15 | Author | ground truth emitted independently | my proof isn't circular | FR-020 | Must | M |
| US-16 | Reader | one command to run everything | I'll actually try it | NFR-007 | Must | M |
| US-17 | Reader | measured numbers with the misses included | I can trust the ones that passed | NFR-001…006 | Must | M |

---

## Cross-Cutting Error Handling

One pattern, defined once; Phase 8 defines the wire format.

| Class | HTTP | Behaviour | Retryable |
|---|---|---|---|
| Validation failure | 422 | Field-level detail, machine-readable code, nothing persisted | No |
| Authorization failure (record-scoped) | 403 | Coarse message, full detail logged with correlation ID | No |
| Not found | 404 | Never distinguishes "absent" from "not yours" | No |
| **Conflict / lost race** | **409** | **The normal, expected outcome of a lost atomic claim. Definite and immediate — never a timeout, never a silent success** | No — the loser must not retry into a double-assignment |
| Idempotency key reuse with different payload | 409 | Original result unchanged | No |
| Offer expired | 410 | Distinguished from 409 because it is a *time* outcome, not a *race* outcome — and the courier's client should surface it differently | No |
| Infrastructure unavailable | 503 | **Fail closed** (NFR-012). `Retry-After` set | Yes |
| Upstream timeout | 504 | Fail closed. Counted | Yes |
| Unexpected | 500 | Correlation ID returned, stack trace logged never returned | Maybe |

**Partial writes are structurally impossible in this design**, and that is the point:
state change and its event share a transaction (FR-013), and cross-service steps are sagas
with idempotent compensation (FR-009) rather than distributed transactions.

Every error response carries `{ code, message, correlationId }`. Codes are stable
identifiers, never prose — the simulator asserts on them.

---

## External Dependencies

| Dependency | Purpose | Criticality | Fallback if unavailable | Cost |
|---|---|---|---|---|
| OpenStreetMap tile server | Map background in the demo UI | **Low** | Markers render on blank background; system unaffected | €0 |
| OSM data extract (Geofabrik, Tunisia) | One-off offline input to the road-graph asset | Medium — at build time only | The generated asset is committed; the extract is never needed at runtime | €0 |
| Maven Central / npm | Build-time dependencies | Medium | Standard build-cache mitigations | €0 |
| Docker Hub | Base and infrastructure images | Medium | Pin digests; images cached locally | €0 |

**There are no runtime third-party dependencies.** No payment provider, no auth provider,
no AI API, no SMS gateway, no managed service. Every runtime dependency is a container the
project starts itself — which is what makes NFR-007 and C-3 achievable, and removes an
entire class of availability and cost risk.

---

## Prioritisation

### Re-triaged 2026-08-09 (pre-Sprint-1 review, item 2)

The original triage rated 22 of 23 FRs (96%) and 11 of 12 NFRs (92%) as Must, and Phase 13
recorded that as justified. **It was not justified — it was prioritisation deferred to the week
the deadline is missed**, which is the worst possible moment to do it. The symptom was a cut
ladder totalling only 15 h, two entries of which turned out to be defective (ADR-0011).

Re-triaged against one test: **would the README still make the argument this project exists to
make without it?**

### Must have — 18 of 23 FRs (78%)

| FR | Why it survives the test |
|---|---|
| FR-001 Create order | Nothing exists without an entry point |
| FR-002 State machine | INV-4 is unprovable without it |
| FR-004 Complete delivery | The normal path by which a courier is released — INV-6's primary case |
| FR-005 Availability | The contended resource's lifecycle |
| FR-006 Candidate search | Contention requires candidates |
| FR-007 Offer lifecycle | The thing being raced for |
| **FR-008 Atomic claim** | **The argument itself** |
| FR-009 Cancellation saga | Property P-2; challenge 3.6 |
| FR-010 Location ingest | Challenge 3.4, and FR-016 has nothing to stream without it |
| FR-011 Durable expiry | Property P-4 |
| FR-012 Accept-vs-expire | The subtlest correctness claim in the system |
| FR-013 Outbox | INV-5 |
| FR-014 Idempotency | Property P-3 |
| FR-015 Invariant counters | Runtime observability of violations — without it, INV-4/5 are lab-only |
| FR-016 WebSocket stream | **Carries goal G-5** — WebSocket scaling is one of three stated learning targets |
| FR-018 Simulator core | No load, no contention, no proof |
| FR-020 Ground-truth emission | **The non-circularity claim** (threat E-02), and the fallback if FR-017 does not land |
| FR-021 Stress profile | The only thing that makes the race actually occur |

### Should have — 5 of 23 FRs (22%)

| FR | What is genuinely lost | Ladder saving |
|---|---|---|
| FR-017 Reconciliation job | The three-source proof. **FR-020 covers the claim at reduced strength** — see ADR-0010 | 10 h |
| FR-022 Live map | Visual polish. Degraded to static + 5 s polling as baseline (ADR-0011 cut 1) | 4 h |
| FR-023 Operator dashboards | Grafana rendering. Counters and the Prometheus endpoint remain | 4 h |
| FR-019 Calibrated behaviour | Statistical realism. A fixed split still exercises expiry | 3 h |
| FR-003 Query order status | A convenience endpoint. Tests read the database | 1 h |

### NFRs re-triaged — 9 of 12 Must (75%)

**Must:** NFR-001 (assignment latency — *target*, measurement is stretch), NFR-003 (ingest
throughput), NFR-004 (expiry accuracy), NFR-005 (recovery time), NFR-007 (cold start),
NFR-008 (determinism), NFR-009 (test isolation), NFR-010 (traceability), NFR-012 (fail closed).

**Should:** NFR-002 (candidate search p99 — a sub-budget of NFR-001, not independently
load-bearing, 1 h), NFR-006 (500 concurrent connections — *two* instances and a handful of
sockets prove cross-instance fan-out; 500 is a scale claim, not a correctness one, 2 h),
NFR-011 (resource footprint — now provisional pending Sprint-1 measurement).

### Won't have this time

Everything in the Non-Goals list of `02-project-brief.md`, plus the hosted demo (dropped,
ADR-0010) and the k6 load report (stretch).

### The rebuilt cut ladder

| Order | Cut | Saves | Preserved |
|---|---|---:|---|
| 1 | FR-017 three-source reconciliation → FR-020 ground truth only | 10 h | Non-circularity, at reduced strength, stated plainly |
| 2 | k6 load report → p99 from the contention harness | 10 h | Real measured latency, under contention rather than sustained load |
| 3 | FR-023 → Prometheus endpoint only, no Grafana | 4 h | The counters themselves |
| 4 | FR-022 → static map, no polling at all | 4 h | Nothing the argument needs |
| 5 | FR-019 → fixed accept/ignore split | 3 h | Continuous expiry exercise |
| 6 | NFR-006 → 50 concurrent connections | 2 h | The cross-instance fan-out proof |
| 7 | NFR-002 → no dedicated search benchmark | 1 h | The latency budget as a whole |
| 8 | FR-003 → minimal status endpoint | 1 h | Test observability |
| | **Total** | **≈35 h** | |

**≈35 h against the previous 15 h**, and every entry has a verified saving and a stated loss.
Items 1 and 2 are already outside committed scope under ADR-0010, so the ladder available
*within* v0.4.0 is **≈15 h** — which is the number to plan against.

**What is never cut**, unchanged: the atomic claim, durable expiry, the saga, outbox and inbox,
any invariant, any invariant test, the concurrency proof, the chaos proof, the README.

### Must-ratio check

**78% functional, 75% non-functional.** Within the 70–80% band that indicates prioritisation
actually happened.

The previous 96% figure was defended on the grounds that the requirement set had been
pre-filtered by nine non-goals at Phase 3, so removing a Must would remove a *proof*. That
defence was half true and wholly misleading: the set *had* been pre-filtered, but the
conclusion drawn from it — that almost nothing could be demoted — was never tested against a
specific criterion. Applying one produced five demotions in a single pass.

The lesson worth recording, since it generalises: **a high Must ratio defended by a plausible
argument is still a high Must ratio.** The test is not whether the requirements are
individually valuable — they all are — but whether the project's central argument survives
their absence. Five of these do not affect that argument at all.
