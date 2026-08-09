# AI Prompts

**Phase:** 14 · **Date:** 2026-08-08

Task prompts for a coding agent, in build order matching `08-delivery-plan.md`. Every prompt
opens by requiring the agent to read `PROJECT_CONTEXT.md` plus the two or three documents
that task needs — the context is always one read away and always current, so nothing is
duplicated here.

**Never inline the tech stack, entity definitions or API conventions into a prompt.** Those
change, and a copy in twelve prompts is twelve things to update and eleven that get
forgotten. What *is* inlined is the short list of constraints an agent would plausibly
violate if not told directly.

The **Do not** section carries the most weight in every prompt below. It encodes the specific
wrong turns this architecture invites — and in this project most of them are the difference
between a correct system and one that looks correct.

---

## 1. Project scaffold — S1-01, S1-02

**Read first:** `PROJECT_CONTEXT.md`, `docs/project-structure.md`, `docs/coding-standards.md`

**Task:** Create the Gradle multi-module monorepo skeleton and the Docker Compose profiles.
No business logic — modules that build, containers that start.

**Requirements:** NFR-007, NFR-009, NFR-011.

**Constraints:**
- Modules exactly as in `project-structure.md`. `contracts` is the **only** shared module.
- Every Compose service declares a `mem_limit` — the budget in `04-architecture.md` is tight
  and unenforced limits will blow it.
- `depends_on: condition: service_healthy` everywhere. Never `sleep`.
- Three profiles: `core`, `observability`, `tools`. `core` runs **two gateway instances**
  behind a reverse proxy.
- Spotless with google-java-format AOSP, wired into `check`.

**Definition of done:**
- `./gradlew build` succeeds on an empty skeleton.
- `docker compose up` brings all `core` services to healthy in under 2 minutes from a cold
  clone.
- ArchUnit test module exists and runs, even with no rules yet.

**Do not:**
- Do not create a `common` or `shared-domain` module. It is the standard route to a
  distributed monolith and is explicitly forbidden.
- Do not add Lombok. Java 21 records cover the DTO cases and Lombok obscures the layering.
- Do not publish infrastructure ports in any file other than the local Compose file.

---

## 2. Database schema and migrations — S1-03

**Read first:** `PROJECT_CONTEXT.md`, `docs/05-data-model.md`

**Task:** Write Flyway migrations creating all three schemas, tables, enums, constraints and
indexes exactly as specified.

**Requirements:** the whole data model; INV-1, INV-2, INV-3 structurally.

**Constraints:**
- **The partial unique indexes are the point of this task**, not a detail:
  `uq_active_assignment_per_courier`, `uq_active_assignment_per_order`,
  `uq_assignment_per_offer`. INV-1 and INV-2 must be unviolatable by application code.
- Every `CHECK` in the DDL is included — `chk_terminal_consistency` in particular, because
  the INV-4 gauge depends on the equivalence it enforces.
- No cross-schema foreign keys. `assignments.order_id` gets **no** FK.
- `location_history` is partitioned by day with a partition-creation job.
- One Postgres role per service, granted only on its own schema.

**Definition of done:**
- Migrations apply cleanly to an empty database.
- An integration test proves the partial unique index rejects a second `ACTIVE` assignment
  for the same courier.
- An integration test proves `dispatch_svc` **cannot** write `orders.orders`.

**Do not:**
- Do not add an index that is not in the index table of `05-data-model.md`. If you believe
  one is needed, add the access pattern first — an unjustified index slows writes forever.
- Do not make the partial indexes non-partial "for simplicity". A full unique index on
  `courier_id` would forbid a courier from ever having a second assignment.
- Do not add `deleted_at` anywhere. Soft delete interacts badly with the partial indexes.

---

## 3. Transactional outbox and inbox dedup — S1-05, S1-06

**Read first:** `PROJECT_CONTEXT.md`, `docs/05-data-model.md`, ADR-0006 and ADR-0007 in
`docs/decision-log.md`

**Task:** Implement the outbox write path, the polling publisher, and consumer-side dedup.

**Requirements:** FR-013, FR-014, INV-5.

**Constraints:**
- The outbox row is inserted **in the same transaction** as the state change. Not after, not
  in an event listener.
- Publisher: 100 ms poll, batch 100, `FOR UPDATE SKIP LOCKED`, ordered by `(aggregate_id,
  created_at)`. Mark `sent_at` **only after** the broker acknowledges with `acks=all`.
- Consumer: check `processed_messages` by `(message_id, consumer_group)`, and insert the
  dedup row **in the same transaction as the effect**. Commit the Kafka offset only after
  the effect is durable.
- Assert at startup that dedup retention (72 h) exceeds Kafka retention plus max retry.
- Keep the outbox column shape Debezium-router-compatible (`aggregate_type`, `aggregate_id`,
  `event_type`, `payload`) — ADR-0006 depends on it for the CDC migration path.

**Definition of done:**
- Test: roll back the transaction, assert no outbox row exists.
- Test: stop the publisher 60 s, restart, assert all events publish in per-aggregate order.
- Test: deliver the same message 100 times **in parallel**, assert one effect and 99
  `duplicate_suppressed_total` increments.

**Do not:**
- Do not publish to Kafka inside the transaction. That is the dual-write the outbox exists
  to eliminate.
- Do not use `@TransactionalEventListener` as a substitute for the outbox — it does not
  survive process death.
- Do not commit the Kafka offset before the effect is durable.
- Do not dedup sequentially in a test. Duplicates arrive concurrently in reality, and a
  sequential test passes against a broken implementation.

---

## 4. Atomic claim — S2-05, S2-06, S2-07 · **the most important task in the project**

**Read first:** `PROJECT_CONTEXT.md`, ADR-0004 in `docs/decision-log.md`,
`docs/06-api-contract.md` (accept endpoint), `docs/05-data-model.md`

**Task:** Implement `AtomicClaimExecutor` — accepting an offer claims courier and order
together, atomically.

**Requirements:** FR-008, FR-012, INV-1, INV-2, INV-3, NFR-012.

**Constraints:**
- **One transaction. Three conditional statements. Affected-row count is the decision.**
  Sequence exactly as in ADR-0004.
- The offer update's `WHERE` carries **all four** predicates: `id`, `courier_id = :caller`
  (authorization), `status = 'OFFERED'`, `expires_at > now()`. Every one is load-bearing;
  dropping any is a silent semantic change.
- Zero rows on the offer update → `410 OFFER_EXPIRED`. Zero rows on the courier update →
  `409 COURIER_UNAVAILABLE`. Both roll back.
- A lost claim is a **return value** (`ClaimResult`), never an exception. Under stress it is
  the majority outcome.
- All time comparisons use database `now()`, never JVM wall-clock.
- Fail closed: if the precondition cannot be verified, no assignment is created.

**Definition of done:**
- 5 000 concurrent accept attempts against 50 couriers → at most 50 assignments, zero
  invariant violations, **and `failedClaims > 0` asserted**.
- The same accept replayed 1 000 times in parallel → exactly one assignment.
- An accept at t=14.98 s racing a sweeper at t=15.00 s converges deterministically, tested in
  both commit orderings.
- Every rejected attempt receives a definite status code, never a timeout.

**Do not:**
- **Do not read the courier, check availability, then update.** That is the exact defect this
  project exists to disprove, and it will pass every non-concurrent test.
- Do not use a Redis lock, a Postgres advisory lock, or `SELECT … FOR UPDATE` followed by a
  separate update. ADR-0004 rejected all three, with reasons.
- Do not check authorization in a separate query before the claim. Fold it into the `WHERE`.
- Do not move the `expires_at` check into application code after the update. It must be a SQL
  predicate or the race is unresolvable.
- Do not retry a lost claim. The loser must not retry into a double-assignment.

---

## 5. Candidate search — S2-02, S2-03

**Read first:** `PROJECT_CONTEXT.md`, ADR-0003 and its Phase 6 amendment,
`docs/architecture-review.md` finding F-3

**Task:** Implement `RedisGeoCandidateFinder` and the Redis structures behind it.

**Requirements:** FR-006, NFR-002.

**Constraints:**
- **Two Redis structures with distinct owners.** `geo:couriers` holds **every** courier's
  position and is written by `tracking-service`. `set:available` holds available courier IDs
  and is written by `dispatch-service`. Candidate search intersects them.
- Exclude couriers already offered this order, and cap at N within radius R.
- Ties at equal distance break deterministically by courier ID — seeded runs must reproduce.
- Empty result is not an error; it drives the order toward `UNASSIGNABLE`.
- Implement geo-index rebuild from `couriers.last_position` on startup.

**Definition of done:**
- p99 < 50 ms for nearest-5-within-3 km across 300 couriers.
- Test: flush Redis, restart, assert the index rebuilds and dispatch resumes.
- Test: a stale entry (courier since gone busy) causes a failed claim and the next candidate
  is taken — **not** an incorrect assignment.

**Do not:**
- Do not have `tracking-service` write `set:available` or read courier status. That was the
  F-3 boundary violation.
- Do not treat the geo index as authoritative. It may lie; the claim cannot.
- Do not use PostGIS for this path — 100 GiST index updates/s is the write amplification
  NFR-003 forbids.

---

## 6. Durable expiry and the accept-vs-expire race — S3-01, S3-02, S3-11

**Read first:** `PROJECT_CONTEXT.md`, ADR-0005, `docs/03-prd.md` FR-011 and FR-012

**Task:** Implement `OfferExpirySweeper` and make expiry and acceptance converge
deterministically.

**Requirements:** FR-011, FR-012, INV-4, NFR-004.

**Constraints:**
- Deadline lives in `offers.expires_at`, written with the offer. **Never an in-process timer.**
- Sweeper: 250 ms tick, batch 500 adaptive to a 2 000 ceiling, `FOR UPDATE SKIP LOCKED`, no
  leader election.
- **Expiry is authoritative.** A late accept is rejected even if it reaches the database
  first (A-07).
- Both accept and expiry issue a conditional update on `offer.status`; the loser observes
  zero affected rows and takes a defined no-op branch.
- Export `sweeper_lag_seconds`; alert at 0.5 s, below the 1 s SLO.
- Its own connection pool of 4, separate from the claim pool.

**Definition of done:**
- Expiry fires within ±1 s of the deadline under normal load.
- Kill the service at t=7 s of a 15 s offer, restart at t=12 s → expiry still within ±1 s of
  t=15 s.
- Kill at t=7 s, restart at t=20 s → expiry fires immediately on recovery, delay bounded and
  observable.
- Two sweeper instances → each overdue offer expires exactly once.

**Do not:**
- Do not use `ScheduledExecutorService`, Quartz, or a Redis ZSET for the deadline. Only the
  row survives what this must survive.
- Do not add leader election. `SKIP LOCKED` is the coordination.
- Do not compare against `System.currentTimeMillis()`. Clock skew between instances is why
  database time is mandatory.
- Do not let the sweeper and the claim share a connection pool.

---

## 7. Cancellation saga — S3-05

**Read first:** `PROJECT_CONTEXT.md`, ADR-0008, `docs/03-prd.md` FR-009

**Task:** Implement `CancellationSaga` with persisted state and resumable compensation.

**Requirements:** FR-009, INV-6.

**Constraints:**
- Saga state is a row. `current_step` is the resumption point.
- `UNIQUE (aggregate_id, saga_type, trigger_event_id)` — a duplicated trigger cannot start a
  second saga.
- Steps 1–3 (cancel assignment, release courier, re-add to `set:available`) are **one Postgres
  transaction**. Only the cross-service return-to-pool is genuinely async.
- Every compensating step is idempotent — running it twice equals running it once.
- On startup, scan for `STARTED` sagas and **resume from `current_step`**, never from the
  beginning.
- Exhausted retries → `FAILED_NEEDS_ATTENTION` plus a Prometheus counter. Never silent
  abandonment.

**Definition of done:**
- Compensation replayed 100 times → one release, courier appears once in candidate results.
- Kill mid-saga, restart, assert it resumes from the last completed step.
- Cancelling a `DELIVERED` order → `409 ORDER_TERMINAL`, no compensation runs.

**Do not:**
- Do not implement this as choreography. "Where is this saga?" must be answerable by a query.
- Do not re-run completed steps on resume and rely on idempotency to make it safe. Idempotency
  is the safety net, not the mechanism.
- Do not release the courier outside the transaction that cancels the assignment — INV-6 is
  load-bearing for INV-1.

---

## 8. Location ingest, hot and cold paths — S4-05, S4-06

**Read first:** `PROJECT_CONTEXT.md`, `docs/03-prd.md` FR-010, `docs/05-data-model.md`

**Task:** Implement the two write paths for courier positions.

**Requirements:** FR-010, NFR-003.

**Constraints:**
- Hot path: Redis `geo:couriers` + `hot:pos:{id}`, p99 < 20 ms. **Never blocks on the cold
  path.**
- Cold path: buffer and flush in batches to partitioned `location_history`.
- Out-of-order reports keep the **highest `recorded_at`**. Mobile networks reorder.
- Postgres write rate must be **≥ 10× below** the ingest rate — assert it in a test.
- On crash, at most one flush window of history is lost; count it in a metric.
- Redis unavailable → `503`, positions dropped and counted. **Never queued.**

**Definition of done:**
- 100 msg/s sustained with the write-rate ratio asserted.
- Test: out-of-order arrival does not overwrite a fresher position.
- Test: duplicate `(courier_id, recorded_at)` produces one history row.

**Do not:**
- Do not write to Postgres synchronously on the ingest path. That is the write amplification
  the whole design avoids.
- Do not queue positions when Redis is down — a backlog of stale positions is worse than none.
- Do not add a GiST index on `location_history.position`. It is deliberately absent.

---

## 9. WebSocket fan-out — S4-07, S4-08, S4-09

**Read first:** `PROJECT_CONTEXT.md`, ADR-0007, `docs/06-api-contract.md` (stream endpoint)

**Task:** Implement WebSocket termination and cross-instance fan-out via Redis Pub/Sub.

**Requirements:** FR-016, NFR-006.

**Constraints:**
- Channel per order: `loc:order:{orderId}`. Subscribe only to channels with live subscribers.
- **Reconnect sends current state, never a replay.** Positions are current-state, not an
  event stream.
- Per-connection bounded queue of 100; on overflow drop the **oldest** and count it.
- Global cap 2 000 concurrent per instance.
- Redis Pub/Sub unavailable → send a `degraded` frame. Never stall silently.
- Position older than 60 s → `stale: true` rather than suppression.

**Definition of done:**
- **Two gateway instances**: a socket on A receives a position produced on B, within 1 s.
- 500 concurrent connections at p95 < 1 s.
- Test: disconnect, reconnect, assert current position arrives and no backlog is replayed.

**Do not:**
- Do not route positions through Kafka. ADR-0007 explains why per-instance consumer groups
  for disposable processes is the wrong shape.
- Do not use sticky sessions to avoid the fan-out problem — that deletes the problem instead
  of solving it, and cross-instance fan-out is a stated learning target.
- Do not replay missed positions.

---

## 10. Simulator — S4-01 … S4-04

**Read first:** `PROJECT_CONTEXT.md`, `docs/03-prd.md` FR-018…FR-021, `docs/01-discovery.md`

**Task:** Build the deterministic courier simulator with ground-truth emission.

**Requirements:** FR-018, FR-019, FR-020, FR-021, NFR-008.

**Constraints:**
- **One RNG stream, seeded.** Same seed and config → byte-identical output. Determinism is a
  requirement, not a convenience.
- Couriers walk the precomputed OSM road-graph asset. Never straight lines. Never a runtime
  routing engine (A-01).
- 60% accept / 25% ignore / 15% decline, ±3 pp over ≥ 1 000 offers. ~5% post-accept cancel.
- Poisson arrivals with a configurable 2–3× evening peak.
- Ground truth written to a **JSONL file on a volume** — not a database table, not a shared
  schema.
- Stress profile: ≥ 10 orders per available courier in a radius tighter than the search radius.

**Definition of done:**
- Two runs with the same seed produce byte-identical trajectories and arrival timings.
- Stress profile produces **observed contention** — failed claims > 0.
- Ground truth and system state match one-to-one with no unmatched records either way.

**Do not:**
- Do not touch Postgres or Redis directly. The simulator is a client and reaches the system
  only through its public API — that independence is what makes FR-020's ground truth mean
  anything.
- Do not use `ThreadLocalRandom`, `Math.random()`, or parallel streams over courier state.
  Any of them destroys determinism.
- Do not run more than one simulator instance.

---

## 11. Reconciliation job — S5-01, S5-02

**Read first:** `PROJECT_CONTEXT.md`, `docs/architecture-review.md` finding F-2,
`docs/07-security.md` threat E-02

**Task:** Build the three-source reconciliation job.

**Requirements:** FR-017, INV-5.

**Constraints:**
- **Three independent sources:** Postgres live state, Kafka topics consumed from **offset 0**
  in a dedicated group, and the simulator ground-truth sink.
- **Imports no service domain code.** Its own SQL, its own consumer. Enforced by ArchUnit.
- Classify in-flight inconsistency (assignment exists, order not yet `ASSIGNED`) separately
  from genuine variance — otherwise it cries wolf on every live run.
- Report is paginated by cursor.

**Definition of done:**
- Zero unexplained variance after a clean simulator run.
- **A deliberately corrupted row is detected** — the job must be able to fail.
- ArchUnit test proves no service package is imported.

**Do not:**
- **Do not compare Postgres state against the Postgres outbox and call it reconciliation.**
  They are written in one transaction and cannot disagree. That was the Phase 6 Blocker: it
  would have passed, reported zero, and proven nothing.
- Do not reuse repositories, entities or mappers from any service.

---

## 12. Proof suites — S2-09, S3-09, S5-03, S5-04

**Read first:** `PROJECT_CONTEXT.md`, `docs/08-delivery-plan.md` testing strategy,
`docs/07-security.md` threats E-01…E-08

**Task:** Build the concurrency, chaos and load suites and the report generator.

**Requirements:** every invariant; NFR-001, NFR-004, NFR-005, NFR-006.

**Constraints:**
- Testcontainers for real Postgres, Redis, Redpanda. **No infrastructure mocks, ever.**
- Toxiproxy for both hard partition **and latency injection** — cascading timeouts cause more
  outages than hard failures.
- Chaos tests assert on **converged state after a bounded settle window**, never on transient
  state during recovery.
- Every invariant counter must be observed non-zero at least once via deliberate injection.
- The results table is **generated from harness output**, not hand-written, so a missed target
  cannot quietly vanish.

**Definition of done:**
- Concurrency: 5 000 attempts, zero violations, contention observed.
- Chaos: three distinct kill scenarios recover within 30 s with zero loss.
- Load: p99 measured and published **including any miss**.

**Do not:**
- **Do not add `@Disabled` to any test in `proof/`.** CI fails on it. Silencing a proof must
  require deleting it visibly.
- Do not assert on transient state during recovery — that is what makes chaos tests flaky, and
  flaky tests get disabled, which removes the proof entirely.
- Do not wrap a chaos test in a retry.
- Do not omit a missed target from the report. The misses are what make the passes credible.

---

## 13. README as hiring artifact — S5-07

**Read first:** `PROJECT_CONTEXT.md`, `docs/02-project-brief.md` (risk R-10),
`docs/bug-log.md`

**Task:** Write the README. It is the primary deliverable's user interface, not documentation.

**Requirements:** G-3, G-6, BO-3.

**Constraints:**
- **Above the fold, in this order:** one-line statement of what this proves, the architecture
  diagram, the six invariants, the measured numbers table.
- Setup instructions go **below** all of that. The Reader has 5–10 minutes.
- State the objective inversion explicitly (ADR-0001) — feature thinness is deliberate and a
  reader must not mistake it for an unfinished project.
- Numbers table includes **every** target and its measurement, misses included, plus the
  exact command and the hardware they came from.
- At least one bug story: symptom, root cause, fix, and the test that caught it.

**Definition of done:**
- A reader who scrolls only the first screen learns what was proven and what the numbers were.
- Every published number is reproducible by a stated command.

**Do not:**
- Do not open with "## Getting Started". That is the single most common way portfolio depth
  goes unseen.
- Do not claim a pattern without linking the test that proves it.
- Do not omit a missed target. A published miss reads as maturity; a suspicious absence reads
  as a gap.
- Do not invent or embellish the bug story. If `bug-log.md` is empty, say the suites found
  nothing and show the suites.

---

## 14. Self-review before push

**Read first:** `docs/coding-standards.md` review checklist

**Task:** Review the working diff against the checklist. There is no second reviewer — CI and
this pass are the only gates.

**Constraints:** work the 15 checklist items in order. The first six carry invariants.

**Do not:**
- Do not skip items 1 and 2 (read-then-write; complete conditional predicates). They are the
  project's central defect class and the hardest to spot after the fact.
- Do not approve a new index without its access pattern.
- Do not approve a `@Transactional` method that makes a network call.
