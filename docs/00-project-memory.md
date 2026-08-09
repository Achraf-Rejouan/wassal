# Project Memory

Single source of truth for what has been settled. Never contradict a locked decision;
supersede it explicitly instead.

**Project:** Wassal — real-time courier dispatch platform
**Started:** 2026-08-08
**Last updated:** 2026-08-09 (pre-Sprint-1 amendment pass)

> **Session mode:** autonomous. Per the kickoff brief, gates do not pause for approval.
> Decisions made without the user are tagged `[AUTO]` in Assumptions and consolidated into
> the "Decisions made without you" table at the top of `09-review-report.md`.

---

## Phase Status

| # | Phase | Status | Artifact |
|---|-------|--------|----------|
| 0 | Understand the Idea | Approved | (no artifact) |
| 1 | Feasibility Assessment | Approved | feasibility.md |
| 2 | Discovery Interview | Approved | 01-discovery.md |
| 3 | Project Definition | Approved | 02-project-brief.md |
| 4 | Product Requirements | Approved | 03-prd.md |
| 5 | Architecture | Approved | 04-architecture.md |
| 6 | Architecture Review | Approved | architecture-review.md |
| 7 | Data Design | Approved | 05-data-model.md |
| 8 | API Design | Approved | 06-api-contract.md |
| 9 | Security | Approved | 07-security.md |
| 10 | Infrastructure and Cost | Approved | cost-estimation.md |
| 11 | Implementation Standards | Approved | coding-standards.md, project-structure.md |
| 12 | Project Planning | Approved | 08-delivery-plan.md |
| 13 | Engineering Review | Approved | 09-review-report.md |
| 14 | Handoff Pack | Approved | ai-prompts.md, PROJECT_CONTEXT.md |

Status values: `Not started`, `In progress`, `Approved`.

---

## Decision Index

One line per decision. The reasoning lives in `decision-log.md` — do not duplicate it here.

| ADR | Decision | Status | Phase |
|-----|----------|--------|-------|
| 0001 | Architectural depth is the deliverable; complexity is deliberate. Cut features, never depth | Accepted | 1 |
| 0002 | Event-driven microservices over the modular-monolith default; 4 services + simulator | Accepted | 5 |
| 0003 | Redis GEO for the candidate index, PostGIS for history — hybrid with explicit ownership | Accepted | 5 |
| 0004 | Atomic claim in Postgres conditional UPDATE + partial unique indexes, not a distributed lock | Accepted | 5 |
| 0005 | Durable expiry via persisted `expires_at` + 250 ms sweeper with SKIP LOCKED, no leader election | Accepted | 5 |
| 0006 | Polling outbox publisher at 100 ms; CDC deferred with a CDC-compatible schema | Accepted | 5 |
| 0007 | Redis Pub/Sub for position fan-out, Kafka for domain events — guarantee chosen per data class | Accepted | 5 |
| 0008 | Orchestrated saga with persisted state, not choreography | Accepted | 5 |
| 0009 | Hetzner CX32 (~€7.80/mo) for an optional hosted demo, deferred to Sprint 5; recorded walkthrough is the committed deliverable | Accepted | 10 |
| 0010 | **Committed scope is `v0.4.0`** — 238 h nominal / 313 h realistic / 24 weeks at 13 h/week; Sprint 5 remainder is stretch | Accepted | 12 |
| 0011 | Two cut-ladder entries struck as defective — **partial dissent** from the pre-Sprint-1 review's item 1 | Accepted | 12 |

---

## Confirmed Facts

Stated directly in the kickoff brief. Load-bearing — downstream phases treat these as
ground truth.

| # | Fact | Source phase |
|---|------|--------------|
| F-01 | Working name **Wassal**; rename permitted but not required | 0 |
| F-02 | Purpose is portfolio and skills development, not a shipped product. Demonstrated engineering depth is the objective function | 0 |
| F-03 | Domain: delivery orders matched to couriers in real time | 0 |
| F-04 | Five properties must be demonstrated with measurable evidence: correctness under concurrency, correctness under failure, idempotency, durable time, justified async boundaries | 0 |
| F-05 | Seven engineering challenges must survive into the design and may not be designed away (brief §3.1–3.7) | 0 |
| F-06 | Stack locked: Java 21, Spring Boot 3.x, Kafka (Redpanda locally), Redis 7, PostgreSQL 16 + PostGIS, WebSocket + Redis Pub/Sub, OpenTelemetry + Prometheus + Grafana, Testcontainers, Toxiproxy, k6 or Gatling, Docker Compose, React + MapLibre GL with OSM tiles | 0 |
| F-07 | Services: `order-service`, `dispatch-service`, `tracking-service`, `gateway`, `simulator`. Boundaries to be validated in Phase 6, merging any that fail the isolation test | 0 |
| F-08 | Transactional outbox required at the DB→Kafka boundary; polling-publisher vs CDC to be decided with justification | 0 |
| F-09 | In scope: order intake, courier availability, candidate search, offer lifecycle, assignment, cancellation + compensation, live tracking, delivery completion, simulator, observability, test suites | 0 |
| F-10 | Explicit non-goals: payments, ratings, chat, multi-city/region, courier onboarding/KYC, admin back-office, mobile push, multi-order batching and route optimisation, real courier/merchant apps | 0 |
| F-11 | Six invariants INV-1…INV-6 are first-class testable requirements, each with a named test **and** a Prometheus runtime counter | 0 |
| F-12 | NFR targets: assignment p99 < 500 ms @ 50 orders/min with 300 couriers; location ingest 100 msg/s sustained with Postgres writes ≥1 order of magnitude lower; offer expiry ±1 s and restart-durable; recovery to consistency < 30 s after dispatch-service kill with zero lost orders; 500 concurrent WebSocket connections with < 1 s ingest-to-client | 0 |
| F-13 | Simulator is a first-class component: 300 configurable couriers, bounded urban area (Tunis or Sousse), OSM road geometry, 15–40 km/h with stops, 60/25/15 accept/ignore/decline, ~5% post-acceptance cancellation, Poisson arrivals with 2–3× evening peak, deterministic seeding, independent ground-truth emission, plus a stress profile maximising contention | 0 |
| F-14 | Six proof artifacts are requirements with sprint capacity, not extras: concurrency proof, chaos proof, idempotency proof, load report (publishing misses too), reconciliation job, README as hiring artifact including a real bug story | 0 |
| ~~F-15~~ | ~~Solo developer with Claude Code as implementer; ~20 h/week; 10–12 week target (~200–240 h)~~ — **superseded by F-23 on 2026-08-09.** The developer half is unchanged; the velocity and duration figures were aspirational | 0 |
| F-16 | Public GitHub repository, single `main` branch, solo workflow | 0 |
| F-17 | Must run completely locally via `docker compose up` | 0 |
| F-18 | Hosted demo budget ≤ €10/month; if the full stack does not fit, recommend a reduced hosted profile plus a local-full-stack path and state plainly what is dropped | 0 |
| F-19 | Prior experience: event-driven analytics platform with append-only event log, batch ETL, calibrated synthetic data generator, reconciliation job. Assume familiarity with event logs, reconciliation and generator design | 0 |
| F-20 | **No** prior production experience with Kafka operations, distributed locking, or WebSocket scaling. These are learning targets — where a design choice is close, favour the option that teaches more and note that reasoning in the ADR | 0 |
| F-21 | Delivery plan must be 5 sprints × ~2 weeks, each a vertical slice with a one-line goal, scoped backlog, exit criterion and demonstrable outcome. Correctness work precedes visual work; the live map is deliberately withheld until assignment invariants are proven | 0 |
| F-22 | Feasibility estimate: ~260 h of work against a ~200–240 h budget. Resolved by cutting feature scope in the order recorded in `feasibility.md`, never architectural depth | 1 |
| F-23 | **Velocity is 13 h/week, not 20.** Developer is a full-time master's student with parallel commitments: daily English practice, sustained open-source contribution, technical reading | pre-S1 |
| F-24 | **Development environment is Fedora Linux with native Docker** — no Docker Desktop VM between containers and kernel. Memory targets must be derived for, and measured on, this environment | pre-S1 |
| F-25 | Unfamiliar work (the three F-20 gaps, ~65 h) is estimated at **×1.75**; everything else at **×1.15**. Selective, not uniform | pre-S1 |

---

## Assumptions

Filled in without confirmation. `[AUTO]` marks decisions made under autonomous session
rules. Each names what breaks if it is wrong.

| # | Assumption | Impact if wrong | Needs confirming by |
|---|------------|-----------------|---------------------|
| A-01 | **CONFIRMED 2026-08-09 by the S1-12 spike, and cheaper than assumed.** Simulator movement uses a **precomputed OSM road graph** (offline extract → node/edge asset, random walk) rather than a runtime routing engine. *Rationale: identical fidelity for load generation at ~20 h less cost and one fewer container* | **Risk retired.** Real central-Tunis geometry came from one Overpass HTTP request — 29,567 nodes / 33,509 edges, one connected component, 2.6 MB — with no routing engine *and* no pbf/osmium path. Cost ~2 h of the 6 h timebox. The grid fallback was built first and remains as `--synthetic` | **Closed** |
| A-02 | `[AUTO]` The project keeps the name **Wassal**. *Rationale: Arabic for "connect/deliver", short, unclaimed in this space, fits the domain* | Cosmetic only; rename is a find-and-replace before first commit | Phase 3 |
| A-03 | `[AUTO]` **No authentication.** Identity asserted via `X-Courier-Id` / `X-Merchant-Id` header, unverified. Record-level authorization is retained and tested. *Rationale: auth is a solved problem that demonstrates nothing here; costs ~12 h and a container* | Adding JWT validation later is one gateway filter — contained. Phase 9 must frame the omission as a decision | Phase 9 |
| A-04 | `[AUTO]` Simulated city is **Tunis**, ~8 × 8 km central bounding box. *Rationale: denser OSM coverage than Sousse, better contention behaviour, better demo density* | Config value + different OSM extract; under an hour | Phase 7, 12 |
| A-05 | `[AUTO]` Location history retained **7 days** via partition drop; Kafka topics 24 h locally. *Rationale: ~60 M rows exercises partitioning and the cold path without exhausting a laptop disk* | Partition schedule + topic config; trivial | Phase 7 |
| A-06 | `[AUTO]` **Local + CI environments only.** No staging tier. *Rationale: nothing user-facing to stage; a staging tier costs hours and proves nothing* | Phase 10 hosting shape; low impact | Phase 10 |
| A-07 | `[AUTO]` **Expiry wins the accept-vs-expire race** — the deadline is authoritative and a late accept is rejected. *Rationale: makes INV-1 defensible rather than the UX kind; honouring a late accept opens the re-offer window that is exactly the double-assignment hazard. Mechanism is identical either way — only the predicate differs* | One predicate in the state machine, one INV-3 acceptance criterion. Cheap to reverse | Phase 5, 7 |
| A-12 | `[AUTO]` **NFR-011's memory figures are provisional** until measured on native Fedora Docker (`S1-15`). *Rationale: a target derived from an assumption about the environment is provisional until the environment is measured* | Targets restated against the measurement; nine-container risk re-rated at the same time | Sprint 1 |
| A-13 | `[AUTO]` **13 h/week is itself an estimate.** If actual is 10 h/week, `v0.4.0` lands at ~30 weeks and Sprint 4 becomes the natural stopping point | Weekly hours logged from week 1 so this surfaces in October, not January | Sprint 2 |
| A-11 | `[AUTO]` **Exhibition mode** for any hosted deployment: read-only public surface (GET + WebSocket), simulator drives state server-side. *Rationale: with asserted identity (A-03), a public write surface is trivially compromisable; removing it costs €0 and makes the demo more stable* | If public interaction is wanted, real auth becomes mandatory (~12 h + a container) | Phase 10 |
| A-10 | `[AUTO]` **NFR-011's 3 GB `core` sub-target is missed** — measured budget is ≈4.7 GB after the two-gateway change (review F-6/F-7). Recorded as a miss rather than restated. *Rationale: publishing a missed target is required by F-14; quietly moving it is not* | A `minimal` profile without simulator and second gateway would land ≈3.2 GB if the sub-target matters | Phase 13 |
| A-09 | `[AUTO]` Resource footprint target: full stack ≤ 6 GB RAM, `core` profile ≤ 3 GB (NFR-011). *Rationale: brief stated no target; derived from what a 16 GB laptop runs alongside an IDE* | Directly governs Phase 10 hosting feasibility and the €10 ceiling analysis | Phase 10 |
| A-08 | `[AUTO]` No AI/ML anywhere. Candidate ranking is deterministic scoring by distance and availability, not learned. *Rationale: ranking sophistication is explicitly not the subject* | Would add a subsystem with per-token cost and non-determinism, breaking reproducibility | Phase 4 |

---

## Open Questions

| # | Question | Blocks phase | Owner |
|---|----------|--------------|-------|

**None open.** U-1 (hosted demo) closed 2026-08-09 as **dropped** — the recorded walkthrough
is the committed deliverable (ADR-0010). U-2 (Redis-lock comparison) closed as **scheduled**,
not optional — `S2-12`, 4 h, timeboxed (ADR-0004 amendment).

Resolved in Phase 10: Q-05 (recorded walkthrough committed; hosted demo optional,
ADR-0009).

Resolved in Phase 5: Q-03 (hybrid Redis GEO + PostGIS, ADR-0003), Q-04 (polling
publisher, ADR-0006).

Resolved in Phase 2: Q-01 (Tunis, A-04), Q-02 (expiry wins, A-07), Q-06 (7-day retention,
A-05), Q-07 (no authentication, A-03).

---

## Changelog

| Date | Change | Reason |
|------|--------|--------|
| 2026-08-08 | Project initialised | — |
| 2026-08-08 | Phase 0 approved; project named Wassal | Kickoff brief supplied the idea in enough detail that Phase 0 was confirmation rather than discovery |
| 2026-08-08 | Phase 1 approved: `feasibility.md`, verdict **Proceed with concerns** | Scope estimate exceeds budget by ~10–25%; resolved by feature cuts. Road-geometry routing-engine trap identified as the top technical risk |
| 2026-08-08 | ADR-0001 accepted | Records deliberate complexity as a decision rather than an oversight, per session rule 0.2 |
| 2026-08-09 | **Sprint 2 complete — `v0.2.0`.** Atomic claim, geospatial offers, invariant counters. INV-1/2/3 proven under 5,000 / 40 / 500 concurrent attempts with contention asserted. S2-12 benchmark closes learning target G-5 | Exit criteria met |
| 2026-08-09 | **Sprint 1 complete — `v0.1.0`.** Walking skeleton end to end, cold start 23 s | Exit criteria met |
| 2026-08-09 | **Pre-Sprint-1 amendment pass, items 1–6.** Timeline reset: 13 h/week, committed scope `v0.4.0`, 24 weeks to 24 Jan 2027 (ADR-0010). Must ratio re-triaged 96% → 78%. Redis comparison moved to Sprint 2. `bug-log.md` made a generated artifact. NFR-011 marked provisional pending Sprint-1 measurement. 2× over-run rule added | Six external review findings, all applied |
| 2026-08-09 | **Arithmetic error found and corrected** — Sprint 1's items summed to 59 h against a 45 h headline, so the plan's 245 h total was really 260 h. The Phase 13 reconciliation of that gap was a rationalisation | Caught while re-summing sprint totals for the calendar |
| 2026-08-09 | ADR-0011 records a **partial dissent**: cut 3 (300→100 couriers) saved ~0 h and would have broken NFR-003; cut 5 (grid road graph) pre-empted a fallback the `S1-12` timebox already guarantees. Both struck | Reviewer instructed all five cuts be taken; two were defective |
| 2026-08-08 | Phase 14 approved: `ai-prompts.md`, `PROJECT_CONTEXT.md` | **Planning complete.** 14 task prompts in build order; context file at repo root. Implementation may begin with the Sprint 1 walking skeleton |
| 2026-08-08 | Phase 13 approved: `09-review-report.md` | 8 findings (1 Blocker, 4 Major). **Blocker R-01: exhibition mode would have blocked the server-side simulator**, leaving the hosted demo permanently empty. Fixed by moving refusal to the proxy. All Blocker/Major applied. `feasibility.md` amended (R-05): cost concern withdrawn, scope concern confirmed |
| 2026-08-08 | Phase 12 approved: `08-delivery-plan.md` | 5 sprints, 245 h against a 200–240 h budget. Gap reconciled with 5 concrete named cuts totalling 15 h, recommended pre-emptively at Sprint 4 start. Road-graph spike pulled forward into Sprint 1 to retire R-3 early |
| 2026-08-08 | Phase 11 approved: `coding-standards.md`, `project-structure.md` | Gradle monorepo, hexagonal per service, `contracts` the only shared module. 14 automated enforcement rules; 4 unenforceable rules listed honestly with the weakest link named |
| 2026-08-08 | Phase 10 approved: `cost-estimation.md` + ADR-0009 | **Phase 1's cost read corrected** — current pricing fits the full stack in budget (~€7.80/mo), so no reduced profile is needed. Hosted demo deferred to Sprint 5; recorded walkthrough is the committed deliverable |
| 2026-08-08 | Phase 9 approved: `07-security.md` | Two adversaries modelled: conventional, and self-deception (E-01…E-08). 6 amendments applied upstream (A-1…A-6). **Exhibition mode** defined for the hosted profile — removes the spoofing surface rather than defending it, at €0 |
| 2026-08-08 | Phase 8 approved: `06-api-contract.md` | 12 endpoints, FR↔endpoint traceability clean in both directions. Authorization folded into the claim's WHERE clause rather than checked beforehand. 409 vs 410 split to separate race-loss from deadline-loss |
| 2026-08-08 | Phase 7 approved: `05-data-model.md` | 11 tables, 15 access patterns, 5 partial indexes. Invariants INV-1/2/3 realised as DB constraints. Dedup retention (72 h) recorded as a correctness constraint with a startup assertion |
| 2026-08-08 | Phase 6 approved: `architecture-review.md` | 9 findings (1 Blocker, 5 Major). All applied to `04-architecture.md`. ADR-0003 amended (not superseded) for finding F-3. Scale ladder re-based on courier count — user count is meaningless for this system |
| 2026-08-08 | Phase 5 approved: `04-architecture.md` + ADR-0002…0008 | Latency budget decomposed (280 ms of 500 ms). Organising principle: the index may lie, the claim cannot. Only 2 Kafka topics — both justified by a named failure |
| 2026-08-08 | Phase 3 approved: `02-project-brief.md` | 6 falsifiable goals, 12 risks. C-2 (budget) vs scope conflict confirmed by discovery, resolved via feature-cut ladder |
| 2026-08-08 | Phase 4 approved: `03-prd.md` | 23 FRs, 12 NFRs, 17 stories, 6 invariants with traceability matrix. Must-ratio 96% justified and its residual risk stated |
| 2026-08-08 | Phase 2 approved: `01-discovery.md` | Brief answered most of the interview; 5 gaps closed autonomously (A-03…A-08). Q-03/Q-04 correctly deferred to Phase 5 as architecture decisions, Q-05 to Phase 10 |
