# Project Brief: Wassal

**Phase:** 3 · **Date:** 2026-08-08 · **Status:** Approved (autonomous session)

---

## Primary Objective

> This section sits above the product vision deliberately. Every downstream decision serves
> it, and where it conflicts with product convenience, this wins (ADR-0001).

**Wassal exists to produce measurable evidence of distributed-systems engineering
competence.** Specifically, that a system can be built which demonstrably holds these five
properties:

| # | Property | Demonstrated by |
|---|---|---|
| P-1 | **Correctness under concurrency** | Thousands of simultaneous assignment attempts against a deliberately contended courier pool, with zero invariant violations |
| P-2 | **Correctness under failure** | Services killed mid-operation, recovering to a consistent state with nothing lost, in measured time |
| P-3 | **Idempotency** | The same command replayed many times producing exactly one effect |
| P-4 | **Durable time** | Timeouts that fire accurately across process death |
| P-5 | **Async boundaries that earn their keep** | Every service and topic boundary justified by a named failure it isolates or scaling axis it unlocks |

The courier-dispatch domain is the vehicle. It was chosen because it makes all five
properties concrete simultaneously and none of them contrived — dispatch genuinely has a
double-booking hazard, genuinely has bounded-time offers, and genuinely has unreliable
mobile clients that retry.

**The measure of success is the evidence, not the feature set.** A version of Wassal with
richer features and weaker proof is a worse outcome than one with three screens and an
airtight concurrency report.

---

## Vision

A dispatch engine where the hard guarantees are visible rather than claimed. Someone clones
the repository, runs one command, watches 300 simulated couriers move across Tunis while
orders flow to them, then reads a page of measured numbers showing what the system does when
two orders want the same courier in the same millisecond — and what it does when the
dispatcher is killed halfway through deciding.

The end state is not a product with users. It is an artifact that answers, with numbers, the
question every backend interview eventually asks: *have you actually built something where
correctness was hard?*

---

## Problem Statement

Two problems, one technical and one about the author. Both are real; conflating them is how
portfolio projects go wrong.

**The domain problem (the vehicle).** Real-time dispatch has a structural hazard: courier
availability is shared mutable state contended by concurrent order flows. The naive
implementation — check availability, then assign — is wrong, and wrong in a way that is
invisible in development and expensive in production. A courier given two jobs at once
strands a customer. An order that falls between an expiring offer and a dying process sits
forever with nobody looking for it. Systems in this space solve these problems; the
solutions are not obvious, and the correctness argument is the interesting part.

**The author's problem (the actual driver).** Three capabilities are missing from the
author's production experience: **Kafka operations, distributed locking, and WebSocket
scaling.** These are common gaps, they are hard to acquire by reading, and their absence is
difficult to disprove in an interview. Prior work — an event-driven analytics platform with
an append-only event log, batch ETL, a calibrated synthetic data generator and a
reconciliation job — established competence in event-log design, reconciliation and
generator calibration, but exercised none of the three gaps.

What is done today instead: reading, and small isolated exercises. Both fail for the same
reason — **the interactions are the subject.** A distributed-lock exercise proves nothing
about whether the lock holds when the process holding it dies while a Kafka consumer is
replaying an at-least-once message and a WebSocket client is mid-reconnect. Only a system
that contains all three at once can produce that evidence.

---

## Goals

Each is falsifiable, with a number and a verification method.

| # | Goal | Target | Falsified by |
|---|---|---|---|
| **G-1** | **Prove concurrency correctness.** Zero violations of INV-1…INV-6 under the stress profile | 0 violations across ≥ 3 consecutive stress runs of ≥ 5 000 contended assignment attempts each | Any non-zero invariant counter |
| **G-2** | **Prove failure correctness.** The system recovers to a consistent state after a mid-operation kill | Consistency restored in **< 30 s**, zero lost orders, zero invariant violations, for ≥ 3 distinct kill scenarios | An order in a non-terminal state past its SLA after recovery, or any lost order |
| **G-3** | **Hit the latency budget, or publish the miss.** Assignment latency measured, not asserted | **p99 < 500 ms** order-created → first-offer-delivered, at 50 orders/min with 300 couriers | No published number, or a number no reader can reproduce |
| **G-4** | **Make the system runnable by a stranger in one command** | Cold clone → fully running stack in **one command, < 2 minutes**, verified by a CI job that clones fresh | Any manual step, any README instruction beyond `docker compose up` |
| **G-5** | **Close the three experience gaps with artifacts to show for it** | Each of Kafka operations, distributed locking and WebSocket scaling exercised under load and failure, with an ADR explaining the design and a test proving it | A capability present in the diagram but never exercised under adversarial conditions |
| **G-6** | **Produce at least one honest bug story** | ≥ 1 real defect caught by the chaos or concurrency suites, written up in the README with symptom, root cause and fix | An empty `bug-log.md` at project end |

G-6 deserves a note. It cannot be planned into existence, only *captured* — which is why the
mitigation is a log kept from Sprint 2 rather than an activity scheduled for Sprint 5. It is
the single highest-signal item in the repository because it is the one thing that cannot be
produced by copying a reference architecture.

---

## Non-Goals

The entries that surprise are the ones doing work.

| Non-goal | Why, specifically |
|---|---|
| **Being a good product** | Feature thinness is a deliberate consequence of ADR-0001, not an unfinished state. The README must say so, or a reader will misread it |
| **Multi-order batching and route optimisation** | The most tempting rabbit hole in this domain. It is an **optimisation problem, not a distributed-systems problem** — it would consume weeks and demonstrate none of P-1…P-5 |
| **Sophisticated courier ranking** | Deterministic distance-and-availability scoring only. No ML, no learned ranking (A-08). Ranking quality is explicitly not the subject, and non-determinism would break reproducibility |
| **High availability** | Replicas, failover, multi-AZ. Cost of downtime here is genuinely zero. The project must prove it **recovers** correctly, not that it never goes down — a distinction worth stating because they are often conflated |
| **Authentication** | Identity is asserted via header, unverified (A-03). Auth is solved and demonstrates nothing here. Record-level *authorization* is kept, because it interacts with claim logic |
| **A staging environment** | Local plus CI only (A-06). There is nothing user-facing to stage |
| **Backups** | All state is reproducible from a seeded simulator run. Acceptable data loss is total. This is a genuine property, not a shortcut |
| **Real mobile apps, payments, ratings, chat, KYC, admin back-office, push notifications, multi-city** | Each adds surface without adding a class of problem. Full list and reasoning in `01-discovery.md` |

---

## Success Metrics

| Metric | Baseline | Target | Measured by | When |
|---|---|---|---|---|
| Invariant violations under stress | n/a | **0** across all six | Prometheus counters + concurrency suite assertions | Sprint 2 onward, continuously |
| Assignment latency p99 | n/a | **< 500 ms** @ 50 orders/min, 300 couriers | k6 or Gatling load report, published | Sprint 5 |
| Location ingest sustained | n/a | **100 msg/s**, Postgres write rate ≥ 10× lower | Prometheus rate counters on both paths | Sprint 4 |
| Offer expiry accuracy | n/a | **±1 s** of deadline, surviving a restart spanning it | Chaos suite | Sprint 3 |
| Recovery time after dispatch kill | n/a | **< 30 s** to consistency, 0 lost orders | Chaos suite, measured | Sprint 5 |
| WebSocket capacity | n/a | **500 concurrent**, ingest → client **< 1 s** | Load harness | Sprint 4 |
| Reconciliation variance | n/a | **0** unexplained | Independent reconciliation job | Sprint 5 |
| Cold clone → running | n/a | **1 command, < 2 min** | CI job cloning fresh | Sprint 1, enforced thereafter |
| Documented bug stories | 0 | **≥ 1** | `docs/bug-log.md` → README | Sprint 5 |

Every one of these is a number that can be wrong. That is the point — a metric that cannot
fail cannot succeed.

---

## Scope

### In scope

Order intake · courier availability toggle · geospatial candidate search · offer lifecycle
(offer / accept / decline / expire) · atomic assignment · cancellation with saga
compensation · live location tracking with cross-instance fan-out · delivery completion ·
the simulator (first-class component) · the observability stack · the four proof suites
(concurrency, chaos, idempotency, load) · the reconciliation job · the README as artifact.

### Deferred

Not being built now; not rejected on principle. Distinguishing these from out-of-scope
matters — conflating them is how a roadmap rots.

| Deferred item | Revisit when |
|---|---|
| Hosted public demo | Phase 10, once the €10/month ceiling is actually costed. May resolve to a recorded walkthrough instead |
| CDC (Debezium) for the outbox | Phase 5 ADR. Genuinely undecided; weighs the learning-target rule against two more containers |
| Multi-region or geo-partitioned dispatch | Only if the project continues past 12 weeks as a second phase |
| Real authentication (JWT/OIDC) | If the demo is ever hosted publicly with a non-trivial surface |
| Courier ranking beyond distance | Never, on current framing — it is a non-goal, listed here only because readers ask |

### Out of scope

As enumerated in Non-Goals above and `01-discovery.md`. These are not being built, in this
project or a follow-up, because they do not serve the primary objective.

---

## Stakeholders

| Role | Who | Interest | Decision authority |
|---|---|---|---|
| Author / developer / operator | The user | All of it. Owns scope, architecture, timeline | **Final on everything** |
| Implementer | Claude Code | Executes the plan; flags where the plan is wrong | None — advisory |
| **Reader** | A hiring engineer, 5–10 minutes | Can this person build correct distributed systems? | None, but **the audience every artifact is optimised for** |
| Planning agent | This process | Consistency of the document set; surfacing risk | None — advisory. Autonomous decisions tagged `[AUTO]` for review |

---

## Assumptions

Consolidated from `00-project-memory.md`. All `[AUTO]` — decided under autonomous session
rules and pending single-pass review at Phase 13.

| # | Assumption | Impact if wrong | Confirmed? |
|---|---|---|---|
| A-01 | Precomputed OSM road graph, not a runtime routing engine | Sprint 4 grows ~15–20 h, gains an OSRM/GraphHopper container | `[AUTO]` |
| A-02 | Name stays **Wassal** | Cosmetic; find-and-replace pre-commit | `[AUTO]` |
| A-03 | No authentication; asserted identity, record-level authorization retained | One gateway filter to add later. Contained | `[AUTO]` |
| A-04 | City is **Tunis**, ~8 × 8 km central box | Config value + different extract; < 1 h | `[AUTO]` |
| A-05 | Location history 7 days via partition drop; Kafka 24 h | Partition schedule + topic config; trivial | `[AUTO]` |
| A-06 | Local + CI only, no staging | Low; affects Phase 10 shape only | `[AUTO]` |
| A-07 | **Expiry wins** the accept-vs-expire race | One predicate + one acceptance criterion | `[AUTO]` |
| A-08 | No AI/ML; deterministic distance scoring | Would break reproducibility and add per-token cost | `[AUTO]` |

---

## Constraints

Facts the architecture must respect, not preferences it may trade against.

| # | Constraint | Source | Consequence for design |
|---|---|---|---|
| C-1 | **Stack is locked**: Java 21, Spring Boot 3.x, Kafka/Redpanda, Redis 7, PostgreSQL 16 + PostGIS, WebSocket, OTel/Prometheus/Grafana, Testcontainers, Toxiproxy, k6 or Gatling, Compose, React + MapLibre | F-06 | Technology selection ADRs are about *how* to use these, not whether |
| C-2 | **~200–240 hours total**, one developer at ~20 h/week over 10–12 weeks | F-15 | The binding constraint. Phase 1 estimated ~260 h of work against it |
| C-3 | **Must run fully locally via `docker compose up`** | F-17 | No managed-service dependencies anywhere in the critical path. Everything self-hosted |
| C-4 | **Hosted demo ≤ €10/month**, if one exists | F-18 | The full stack (~3–4 GB resident) does not fit. Phase 10 must define a reduced profile and state what it drops |
| C-5 | **Architectural depth may not be reduced to save time**; cut features instead | ADR-0001 | Inverts the normal simplification instinct. Must be re-asserted at every design decision |
| C-6 | **No prior production experience** with Kafka ops, distributed locking, WebSocket scaling | F-20 | Sprints 1 and 4 carry hidden learning cost. Where a choice is close, the option that teaches more wins, and the ADR says so |
| C-7 | **Public repository, single `main` branch, solo workflow** | F-16 | No PR review gate; CI is the only quality gate, so it must be genuinely enforcing |
| C-8 | **Correctness work precedes visual work**; the live map is withheld until assignment invariants are proven | F-21 | A hard sequencing constraint on the delivery plan, defending against the strongest pull toward out-of-order work |

### Where constraints conflict with goals

**C-2 versus the full scope, confirmed.** Phase 1 estimated ~260 hours of work against a
200–240 hour budget — a 10–25% overrun. Discovery **confirmed rather than dissolved** this
concern: no scope was found to be smaller than assumed, and the simulator (35 h, producing
no user-facing feature) was confirmed as a genuine first-class component rather than a test
fixture.

The conflict is resolved by C-5's direction: **cut features, never depth**, in the order
recorded in `feasibility.md` — REST surface, then Grafana polish, then frontend, then
simulator behavioural realism. Each cut removes visible surface while leaving every
invariant and every proof artifact intact.

Residual risk after that resolution: if overrun exceeds ~25%, feature cuts are exhausted and
the only remaining lever is dropping a *sprint*, ending the project at a clean boundary. The
sprint structure exists partly to make that failure mode survivable — see R-2.

**C-4 versus G-4, partially conflicting.** "Runnable by a stranger in one command" is
satisfied locally at zero cost. A *hosted* demo at ≤ €10/month cannot run the full stack.
Phase 10 resolves this; the likely outcome is a reduced hosted profile plus a recorded
walkthrough, with local-full-stack as the canonical experience. Not a blocker, but it must
be stated plainly rather than quietly dropped.

---

## Risks

Includes the unglamorous ones, which are what actually kill solo projects.

| # | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| **R-1** | **Motivation decay at ~70%** — the interesting parts done, proof artifacts and README remaining. The classic solo-project death | **High** | **Critical** — an unfinished portfolio piece is worth close to nothing | Every sprint ends demonstrable and shippable. If time runs out, the project ends at a sprint boundary in a defensible state. Sprint 5 content is *proof*, deliberately front-loaded into earlier sprints where possible | Author |
| **R-2** | Scope overrun beyond the 25% already anticipated | Medium | High | Feature-cut ladder pre-agreed in `feasibility.md`, so the decision is already made when the moment arrives. Beyond that: drop Sprint 5 scope, not architecture | Author |
| **R-3** | **Simulator road-geometry pulls in a routing engine** (Phase 1 risk T-1) | Medium | High — ~20 h and a heavyweight container | A-01: precomputed OSM node/edge graph, random-walked. Build the asset early, from Sprint 1, to remove the Sprint 4 cliff | Author |
| **R-4** | **Chaos tests become flaky and get disabled**, silently removing the P-2 proof | **High** | **Critical** — G-2 evaporates and nothing visibly breaks | Assert on *converged* state after a bounded settle window, never on transient state. Separate CI job allowed to be slow, never allowed to be skipped. Any `@Disabled` on a chaos test is a project-level defect | Author |
| **R-5** | **Frontend scope creep.** The map is the most rewarding and least valuable thing to build | **High** | Medium | Hard 15 h cap. C-8 ordering constraint. One screen, ugly-but-clear | Author |
| **R-6** | Kafka operations learning curve exceeds estimate; consumer-group and offset semantics burn days | Medium | Medium | Redpanda locally (no ZooKeeper/KRaft management). Minimal partitions and consumer groups until Sprint 3. Budget Sprint 1 to run slow | Author |
| **R-7** | **Nine-container stack becomes slow or fragile**, taxing every dev cycle | Medium | Medium — compounds weekly | Health checks with proper `depends_on` conditions. Compose profiles: `core` vs `observability`, so the common case starts less. Cold-start time is a tracked metric (G-4) | Author |
| **R-8** | **Invariant counters exist but are never non-zero**, so observability is theoretical | Medium | Medium — undermines a headline claim | Deliberately inject a violation in a test and assert the counter increments. A counter never seen non-zero is untested code | Author |
| **R-9** | **Reconciliation becomes self-referential**, validating state against the code that produced it | Medium | High — the proof proves nothing | Reconciliation shares no domain code with the services; reads raw tables and the raw event log via its own queries | Author |
| **R-10** | **Depth is real but illegible** — a reader spends 6 minutes and sees none of it | Medium | **Critical** — the whole investment returns nothing | README leads with diagram, invariants and measured numbers. Setup below the fold. Treated as a landing page, not documentation | Author |
| **R-11** | **Key-person dependency, total.** Illness, job change or life event ends the project outright | Medium | High | Unmitigable in substance. Reduced by R-1's structure: value accrues at each sprint boundary rather than at the end | Author |
| **R-12** | Published numbers cannot be reproduced by a reader | Low | High — destroys credibility of everything else | Load profiles committed as code with fixed seeds. README states the exact command and the hardware the numbers came from | Author |

The three to watch: **R-1, R-4 and R-10.** All three share a property that makes them
dangerous — nothing breaks when they happen. No test fails, no build goes red. They degrade
the project silently, which is precisely why they are written down.
