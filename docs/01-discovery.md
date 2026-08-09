# Discovery

> Phase 2. Most of this record comes directly from the kickoff brief, which answered the
> interview in advance. Where the brief was silent, the answer was decided autonomously per
> session rule 0.1 and is marked **`[AUTO]`** with its rationale.

**Project:** Wassal
**Date:** 2026-08-08

---

## Confirmed Understanding

Wassal is a real-time dispatch platform that matches delivery orders to couriers.

An order arrives. The system finds nearby available couriers, offers the job to the best
candidate, and waits a bounded time (default 15 s) for a response. On acceptance the order is
assigned and the customer watches the courier move on a live map until delivery. On decline
or timeout the offer expires and passes to the next candidate. If a courier cancels after
accepting, the order returns to the pool and the courier is released.

In one sentence: **jobs and couriers get matched in real time, and the system must never
lose an order or give one courier two jobs at once.**

**The critical framing, which governs every decision downstream:** this is a portfolio and
skills-development project. The product exists to make five correctness properties concrete
and demonstrable. Where product convenience and demonstrated engineering depth conflict,
depth wins (ADR-0001).

---

## Business Context

### Value proposition

There is no revenue model and no external customer. The value is the artifact: a public
repository that demonstrates, with measurable evidence, distributed-systems engineering
competence — and, secondarily, the skills acquired in building it.

The five properties the system exists to prove, in priority order:

1. **Correctness under concurrency** — no double-assignment, ever, under load.
2. **Correctness under failure** — kill a service mid-operation, recover to a consistent
   state, lose nothing.
3. **Idempotency** — duplicate requests from unreliable mobile networks produce one effect.
4. **Durable time** — timeouts that survive process death.
5. **Async boundaries that earn their keep** — event-driven design justified by
   requirements, not fashion.

### Target users and roles

The system has no human users in production. It has *modelled* roles, which exist because
the domain requires them, and one real audience.

| Role | Who plays it | What they can do |
|---|---|---|
| Merchant / customer | Simulator, and the demo UI | Create an order; watch its status and the courier's position |
| Courier | Simulator (300 instances) | Toggle availability, report location, accept/decline offers, cancel, complete delivery |
| Operator | The developer | Observe the system: Grafana dashboards, invariant counters, reconciliation output |
| **Reader** | **A hiring engineer** | **The real audience. Clones the repo, runs `docker compose up`, reads the README, forms a judgment in 5–10 minutes** |

The Reader row is not decoration. It is the reason the README carries requirement status in
the PRD rather than being treated as documentation.

### Current alternative and its shortcomings

The alternative to building Wassal is acquiring the same three skills — Kafka operations,
distributed locking, WebSocket scaling — by reading, or by building three smaller isolated
exercises.

What is wrong with that: none of the five properties is observable in isolation. A
distributed lock demo proves nothing about whether a lock holds when the process holding it
dies mid-transaction while a Kafka consumer is replaying an at-least-once message. The
interactions *are* the subject. A single system that exhibits all of them under adversarial
load is the only artifact that demonstrates the actual competence.

### Success metrics

| # | Metric | Target | Measured how |
|---|---|---|---|
| S-1 | Invariant violations under the stress profile | **Zero**, across all six invariants | Prometheus counters + assertion in the concurrency suite |
| S-2 | Assignment latency p99 | < 500 ms at 50 orders/min, 300 couriers | k6/Gatling load report |
| S-3 | Recovery time after `dispatch-service` kill mid-assignment | < 30 s to consistency, zero lost orders | Chaos suite, measured and published |
| S-4 | Offer expiry accuracy | Fires within ±1 s of deadline, survives a restart spanning the deadline | Chaos suite |
| S-5 | Reconciliation variance between live state and event log | Zero unexplained variance | Independent reconciliation job |
| S-6 | Cold clone to running system | One command, under 2 minutes | CI job that clones fresh and runs `docker compose up` |
| S-7 | Documented bugs caught by chaos or concurrency testing | **At least one**, written up honestly | `docs/bug-log.md`, surfaced in the README |

S-6 and S-7 are project-success metrics rather than system metrics, and both are
load-bearing. S-6 protects the Reader's five-minute window; S-7 is the highest-signal
content in the repository because it is the one thing that cannot be produced by copying a
reference architecture.

---

## Scope

### In scope for v1

Order intake · courier availability · geospatial candidate search · offer lifecycle
(offer / accept / decline / expire) · assignment · cancellation with saga compensation ·
live location tracking · delivery completion · the simulator · the observability stack ·
the test suites (concurrency, chaos, idempotency, load) · the reconciliation job.

### Explicitly out of scope

Listed as non-goals, not omissions. Each is stated in the PRD so a reader does not mistake
absence for oversight.

| Non-goal | Why it is out |
|---|---|
| Payments | A payments integration is a vendor-API exercise, not a distributed-systems one |
| Ratings and reviews | Pure CRUD. Adds surface, proves nothing |
| In-app chat | A second real-time subsystem that duplicates what WebSocket fan-out already demonstrates |
| Multi-city / multi-region | Would force geo-partitioning decisions that add cost without adding a new class of problem |
| Courier onboarding and KYC | Workflow and compliance, not concurrency |
| Admin back-office | CRUD surface; Grafana covers the operator need |
| Mobile push notifications | Vendor integration; WebSocket already carries the real-time proof |
| **Multi-order batching and route optimisation** | **An optimisation problem, not a distributed-systems problem.** The most tempting rabbit hole in the domain and explicitly forbidden |
| Real courier / merchant mobile apps | The simulator replaces the fleet; native apps add nothing to the thesis |

### Timeline and drivers

Target 10–12 weeks at ~20 h/week: **200–240 hours**. No external deadline, no launch date,
no third party with its own clock. The driver is self-imposed — an artifact that stays
unfinished indefinitely has no value, so the sprint structure exists to guarantee the
project is in a defensible, demonstrable state at every two-week boundary.

Phase 1 estimated ~260 h of work against that budget. The gap is closed by cutting feature
scope in the recorded order (F-22), never architectural depth.

### Team and budget

- **One developer**, with Claude Code as the implementer. AI-assisted throughput is the
  staffing answer; "solo developer" is grounds for ruthless *feature* scoping and for
  automating operational tooling, not for reducing architectural ambition (ADR-0001).
- **Infrastructure budget: €0 locally.** Everything is open-source and Compose-hosted.
  OpenStreetMap tiles via MapLibre carry no key and no metered cost.
- **Hosted demo: ≤ €10/month**, if one exists at all — settled below and costed in Phase 10.

### Experience baseline

| Area | Position | Consequence |
|---|---|---|
| Event logs, append-only design | Familiar (prior analytics platform) | Outbox and event-log design should go quickly |
| Reconciliation jobs | Familiar (prior platform shipped one) | Proof artifact 5 is low-risk |
| Calibrated synthetic data generators | Familiar (prior platform shipped one) | Directly transferable to the simulator's statistical model — though not to its movement model |
| Batch ETL | Familiar | Informs the cold-path batching design |
| **Kafka operations** | **No production experience — learning target** | Expect Sprint 1 to run slow. Redpanda locally removes broker-management burden |
| **Distributed locking** | **No production experience — learning target** | The core of Sprint 2 |
| **WebSocket scaling** | **No production experience — learning target** | Expect Sprint 4 to run slow |

Where a design choice is close, the option that teaches more wins, and the ADR says so
explicitly.

---

## Scale and Performance

| Dimension | At launch | At 12 months | Source |
|---|---|---|---|
| Active couriers | 300 (configurable) | 300 — fixed by the simulator, not a growth curve | Stated (F-13) |
| Order arrival rate | 50 orders/min baseline | 100–150 orders/min at the 2–3× evening peak | Stated (F-12, F-13) |
| Location ingest | 100 msg/s sustained | Same | Stated (F-12) |
| Concurrent WebSocket connections | 500 | Same | Stated (F-12) |
| Orders in Postgres | ~72 k/day at baseline if run continuously | Bounded by demo run length, not by adoption | Derived |
| Location history rows | ~8.6 M/day at 100 msg/s | Bounded by retention window — see below | Derived |
| Stress profile | Many orders converging on few couriers in a small radius | Deliberately maximised contention | Stated (F-13) |

**This project has no growth curve, and that is a meaningful simplification.** Load is
generated, bounded and reproducible. Nothing needs to be sized for uncertainty — every
number above is a design target that can be measured on demand rather than forecast. Where
this project *is* demanding is peak contention and failure behaviour, not volume.

### Latency budgets

| Path | Budget | Note |
|---|---|---|
| Order created → first offer delivered | **p99 < 500 ms** | The headline number. Decomposed in Phase 5 |
| Candidate search | Target < 50 ms | Sub-budget of the above; drives Q-03 |
| Location ingest → subscribed client | **< 1 s** | Hot path only; never touches Postgres synchronously |
| Offer expiry firing accuracy | **±1 s** of deadline | Sets the sweeper's tick interval |
| Recovery to consistency after kill | **< 30 s** | Bounds sweeper interval and consumer restart time |

### Cost of downtime

Genuinely nothing. No revenue, no safety implication, no users. This is important and
liberating: it means availability engineering (multi-AZ, replicas, failover) is **out of
scope as an objective**, while failure *recovery* is squarely in scope as a demonstration.
The project must prove it recovers correctly, not that it never goes down.

---

## Identity and Access

`[AUTO]` — resolves **Q-07**. The brief did not specify authentication.

**Decision: no real authentication in v1. Identity is asserted, not verified.**

Requests carry a `X-Courier-Id` or `X-Merchant-Id` header, trusted without verification. The
gateway treats these as identity for authorization scoping (a courier may only accept an
offer addressed to them, may only report their own location) but performs no credential
check.

*Rationale:* authentication is a solved problem that demonstrates nothing this project
exists to demonstrate. Adding Keycloak or a JWT issuer costs 10–15 hours and one more
container, and every one of those hours comes out of the proof artifacts. The **authorization
scoping is kept** — because "a courier may only accept offers addressed to them" is a real
correctness rule that interacts with the claim logic and is worth enforcing and testing.

*Impact if wrong:* adding JWT validation at the gateway later is a contained change — one
filter, one config block, no domain impact. Cheap to reverse, which is why it is a safe
`[AUTO]`.

Phase 9 documents this as an accepted risk with an explicit "what production would require"
section, so the omission reads as a decision rather than a gap.

| Question | Answer |
|---|---|
| Authentication mechanism | None. Asserted identity via header `[AUTO]` |
| Authorization model | Record-level, not merely role-based: a courier may act only on offers addressed to them and locations belonging to them |
| Multi-tenant | No. Single logical tenant, single city (non-goal F-10) |
| External identities | None. No third-party webhooks, no API keys, no service accounts |
| Invitations / permission hierarchy | None |

The record-level authorization point matters more than it looks. Retrofitting record-scoped
checks onto a system built for role checks touches every query — deciding now that offer
acceptance is scoped to the addressed courier keeps the claim logic honest from Sprint 2.

---

## Feature Surface

| Capability | In scope | Notes |
|---|---|---|
| **AI / ML** | **No** | No model, no inference, no per-token cost. The intelligence is dispatch logic — deterministic scoring by distance and availability, not learned ranking. Ranking sophistication is explicitly *not* the subject |
| **Search** | Geospatial only | Nearest-N-within-radius over available couriers. No full-text search anywhere in the system |
| **Notifications** | In-app / WebSocket only | Offers reach couriers over the simulator's channel; status reaches customers over WebSocket. No email, no SMS, no push (F-10) |
| **Payments** | No | Non-goal |
| **File handling** | No | No uploads anywhere. Removes an entire threat-model class |
| **Integrations** | One, read-only, offline | OpenStreetMap: tile serving for the map, and a one-off extract for the simulator's road graph. No runtime third-party API calls at all |
| **Analytics** | Operator dashboards only | Prometheus + Grafana. No product analytics, no user-facing reporting |
| **Offline** | No | Nothing works offline; the simulator assumes connectivity |
| **Real-time** | **Yes — central** | Live location fan-out to subscribed clients over WebSocket with cross-instance Redis Pub/Sub. This is challenge 3.5 and a headline capability |

**No AI, no payments, no file uploads, no third-party runtime dependencies.** That is an
unusually clean surface, and it concentrates all of the difficulty in exactly the place the
project wants it: concurrency, durability and distribution.

### The simulated city

`[AUTO]` — resolves **Q-01**. **Tunis**, with a bounding box over the central metropolitan
area (roughly Lac / Centre-ville / Bab Bhar), approximately 8 × 8 km.

*Rationale:* Tunis has denser and better-mapped OSM road coverage than Sousse, which
directly improves the road-graph asset the simulator walks. The denser street grid also
produces better contention behaviour in the stress profile — couriers cluster more
realistically in a dense network than in a sparse one. Visually, a dense grid reads better
on a demo map.

*Impact if wrong:* the city is a configuration value plus a different OSM extract. Under an
hour to change. Bounding box, tile centre and the extract path all live in one config file.

---

## Compliance and Data

| Question | Answer |
|---|---|
| Regulated data | **None.** No health, financial, children's or biometric data |
| Personal data stored | **None real.** All couriers, merchants and orders are simulator-generated. Names are synthetic; locations are synthetic paths on a public road graph |
| GDPR position | Not engaged — no natural person's data is processed. There is no data subject |
| Data residency | Unconstrained. Local Docker, or a single VPS anywhere |
| Audit trail | **Required — for engineering reasons, not compliance.** The append-only event log is what the reconciliation job checks live state against. Its purpose is proving INV-5, not satisfying a regulator |
| Retention | See below `[AUTO]` |

That the audit trail is required for correctness rather than compliance is worth stating
explicitly, because it changes its design. It must be *complete and independently
queryable* (so reconciliation cannot be self-referential), but it does not need tamper
evidence, signing, or a legally-defensible retention period.

### Retention

`[AUTO]` — resolves **Q-06**.

| Data | Retention | Rationale |
|---|---|---|
| Location history (`location_history`) | **7 days**, enforced by dropping partitions | At 100 msg/s a week is ~60 M rows — enough to exercise partitioning and demonstrate the cold path, bounded enough that a laptop disk survives a long run |
| Domain event log | **Full retention within a run**; truncated by `docker compose down -v` | Reconciliation must see the whole history of a run to be meaningful |
| Orders, assignments, offers | Full retention within a run | Small volume |
| Kafka topics | 24-hour retention locally | Replay within a session is useful; long retention is not, and disk is the constraint |

*Impact if wrong:* retention is a partition-drop schedule and a Kafka topic config. Trivial
to change; the design cost is only that partitioning must exist, which it should anyway to
demonstrate the cold path properly.

---

## Operations

| Question | Answer |
|---|---|
| Where it runs | Primarily **local Docker Compose** (F-17). Optionally one small VPS for a reduced hosted demo (F-18), decided in Phase 10 |
| Existing infrastructure / vendor commitments | None. Greenfield, no constraints to fit into |
| Environments | **Local only**, plus CI. No staging, no production. `[AUTO]` — there is no user-facing service to stage, and a staging tier would consume hours while proving nothing |
| Who operates it after launch | The developer, not on call. Nothing runs unattended; the system is started to be demonstrated |
| Backups and recovery | **No backups.** All state is reproducible from a seeded simulator run. Acceptable data loss is total; acceptable recovery time is one `docker compose up`. This is a genuine property of the project, not a shortcut |
| CI | GitHub Actions: build, unit tests, Testcontainers integration suite on every push. Chaos and load suites run on a slower separate workflow (see risk T-3) |

### What would be genuinely unacceptable

This seeds the Phase 9 threat model. Since there is no confidential data and no revenue, the
unacceptable outcomes are all **epistemic** — the system claiming something untrue:

1. **A silent invariant violation.** A double-assignment that happens and is not counted is
   worse than one that happens and is caught, because it makes every published number a
   lie. This is the single worst outcome in the project.
2. **A self-referential proof.** A reconciliation job that validates state against the same
   code path that produced it, or a test that asserts what the implementation happens to do.
   Both produce confident, meaningless green.
3. **A published number that cannot be reproduced.** A p99 in the README that a reader
   cannot obtain by running the load profile destroys the credibility of everything else in
   the document.
4. **An order stuck forever.** INV-4's failure mode; the most visible sign that durable time
   does not work.
5. **A demo that does not start.** For the Reader, a failed `docker compose up` is
   indistinguishable from a project that does not work.

Note how different this list is from a normal threat model. The adversary here is not an
attacker, it is **self-deception** — and Phase 9 treats it accordingly while still covering
conventional controls for the hosted surface.

---

## Assumptions Requiring Confirmation

| Assumption | Why it matters | Which phase needs it settled |
|---|---|---|
| `[AUTO]` Precomputed OSM road graph instead of a runtime routing engine (A-01) | ~20 h and one container; the top technical risk from Phase 1 | 12 |
| `[AUTO]` Name stays **Wassal** (A-02) | Cosmetic; find-and-replace before first commit | 3 |
| `[AUTO]` No authentication; asserted identity via header, record-level authorization retained | Removes a container and ~12 h; Phase 9 must frame it as a decision | 9 |
| `[AUTO]` City is **Tunis**, ~8 × 8 km central bounding box | Drives the OSM extract and demo density | 7, 12 |
| `[AUTO]` Location history retained **7 days** via partition drop | Sets partitioning strategy and disk ceiling | 7 |
| `[AUTO]` **Local + CI only**; no staging environment | Saves hours; nothing to stage | 10 |
| `[AUTO]` A **late accept always loses** to a fired expiry (see below) | Determines the offer state machine and INV-3's acceptance criteria | 5, 7 |

### The accept-vs-expire race

`[AUTO]` — resolves **Q-02**, and it is the most consequential `[AUTO]` in this document.

The brief requires that the accept-at-14.98s versus sweeper-fires race "converge on one
outcome deterministically" but does not say *which* outcome. Two coherent answers exist:

| Option | Behaviour | Pros | Cons |
|---|---|---|---|
| **Expiry wins** | Once the deadline passes, a late accept is rejected even if it arrives first at the database | Deadline means what it says. The courier who accepted at 14.98 s may have already been re-offered elsewhere — honouring the late accept risks INV-1 | A courier who accepted within the window sees a rejection; feels unfair |
| Accept wins | A late accept is honoured if it lands before the sweeper commits | Kinder to the courier | The deadline becomes advisory. Worse: it opens a window where the order has already been offered onward, which is exactly the INV-1 hazard |

**Decision: expiry wins. The deadline is authoritative.**

*Rationale:* it is the choice that makes the invariant defensible rather than the one that
makes the user experience kinder — the correct priority for this project. It also produces
the cleaner implementation: both paths funnel through a single conditional transition on
`offer.status`, and whoever observes zero affected rows takes the no-op branch. No
coordination, no distributed agreement, no ambiguity.

Critically, **the mechanism is identical either way** — the same single-conditional-write
design serves both, and only the predicate differs. So this decision is genuinely cheap to
reverse: one predicate change plus a test update.

*Impact if wrong:* one line in the state machine, one acceptance criterion in INV-3.

---

## Open Questions

Everything from Phase 1 is resolved here or explicitly deferred to the phase that owns it.

| # | Question | Blocks phase | Status |
|---|---|---|---|
| Q-01 | Simulated city | 7, 12 | **Resolved** — Tunis `[AUTO]` |
| Q-02 | Accept-vs-expire race resolution | 5, 7 | **Resolved** — expiry wins `[AUTO]` |
| Q-03 | Redis geo vs PostGIS vs hybrid | 5, 7 | Deferred to Phase 5 — it is an architecture decision needing a read/write-ratio analysis, and gets a full ADR |
| Q-04 | Polling publisher vs CDC for the outbox | 5 | Deferred to Phase 5 ADR. Weighs the learning-target rule (F-20) against the container budget |
| Q-05 | Hosted demo required at all? | 10 | Deferred to Phase 10, where the €10 ceiling is actually costed |
| Q-06 | Location retention | 7 | **Resolved** — 7 days, partition drop `[AUTO]` |
| Q-07 | Authentication | 9 | **Resolved** — none; asserted identity, record-level authorization retained `[AUTO]` |

---

## Three Constraints That Will Most Shape the Design

1. **Depth is the objective function, not features** (ADR-0001). This inverts normal
   engineering judgment and must be re-stated at every design decision, or the pull toward
   simplification will quietly win.
2. **~260 hours of work against ~240 available.** Every phase from here must be scoped as if
   time will run out, because it will. Vertical slices with real exit criteria are the
   mechanism.
3. **Two of the three hardest components sit in areas with no prior production
   experience** — Kafka operations and WebSocket scaling. Sprints 1 and 4 carry hidden
   learning cost that their content does not reveal.

## What Concerns Me

- **The simulator is the largest single component and produces no user-facing feature.** It
  is a full 35-hour build wearing the costume of a test fixture, and it is where scope
  overruns will originate.
- **Chaos tests are the most likely thing to be quietly disabled.** They are slow, they are
  flaky if written against transient state, and nothing breaks when they are skipped. The
  proof of property 2 disappears the day that happens.
- **The map will pull effort forward out of sequence.** The ordering constraint is correct
  and will be uncomfortable to hold to in week 5.
