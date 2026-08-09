# Feasibility Assessment

> **Amended 2026-08-08 by the Phase 13 engineering review (finding R-05).** The Phase 1
> verdict has been re-tested against what discovery, architecture and costing actually found.
> Two corrections, recorded here rather than edited into the text below, so the original
> directional read stays visible:
>
> **1. The cost concern is withdrawn.** This document concluded the €10/month ceiling could
> not host the full stack and that a reduced hosted profile would be needed. Phase 10 found
> current pricing puts an 8 GB / 4 vCPU instance at ~€6.80/month, which runs the **complete**
> stack — both gateways, server-side simulator, observability tier — at ~€7.80/month all-in.
> **No reduced profile is required and nothing is dropped.** The original estimate appears to
> have been anchored on managed-platform pricing rather than raw VPS pricing.
>
> **2. The estimate is refined, and the scope concern is confirmed.** Phase 1's top-down
> figure was ~260 h; Phase 12's bottom-up sprint plan totals **245 h**. The 15 h difference is
> the road-graph simplification (A-01) landing in the detailed plan. Against a 200–240 h
> budget the gap persists, so the scope concern **stands** — but it now has five concrete
> named cuts totalling 15 h in `08-delivery-plan.md`, rather than a general warning.
>
> **Verdict unchanged: Proceed with concerns.** The remaining concern is scope, not cost.
>
> ---
>
> **Second amendment 2026-08-09 (pre-Sprint-1 review, item 1).** The scope concern is now
> **quantified against realistic velocity rather than nominal budget**, and it is worse than
> either the Phase 1 or Phase 13 reading.
>
> Three compounding corrections:
>
> **a. The delivery plan's arithmetic was wrong.** Sprint 1 was headlined at 45 h; its items
> sum to **59 h**. The plan's 245 h total was really **260 h**. The Phase 13 reconciliation
> that explained the 260-vs-245 difference as the road-graph simplification was a
> rationalisation of a mis-addition.
>
> **b. Unfamiliar work was estimated as if it were familiar.** Three named experience gaps
> (F-20) touch ~65 h of tasks. Applying a 1.75× multiplier there and 1.15× elsewhere — rather
> than uniformly, which would be equally unexamined — the original scope is **≈340 h**.
>
> **c. The 20 h/week assumption was wrong.** The developer is a full-time master's student
> with parallel commitments; realistic sustained availability is **13 h/week**.
>
> At 340 h and 13 h/week the original plan was **~26 weeks**, not 10–12. Its failure mode was
> not a Sprint 5 overrun but abandonment around week 11 at two-thirds complete — precisely
> risk R-1, which the plan itself had rated highest.
>
> **Resolved by resetting the finish line, not by cutting** (ADR-0010): committed scope is
> `v0.4.0` at **238 h nominal / 313 h realistic / 24 weeks**, with Sprint 5's remainder as
> stretch. The "hard part is the evidence, not the mechanisms" finding at the top of this
> document survives intact — it is why the proof subset stayed in committed scope while the
> reconciliation job and load report moved out.
>
> **Verdict still unchanged: Proceed with concerns.** The concern is now sized correctly.


> Directional assessment based on the confirmed idea, before the discovery interview.
> Every number here is an order of magnitude with a stated basis. Re-tested in Phase 13
> against what discovery actually established.

**Project:** Wassal — real-time courier dispatch platform
**Date:** 2026-08-08
**Phase:** 1

---

## Verdict

**Proceed with concerns.**

The engineering ambition is well-matched to the stated purpose, and none of the seven named
challenges in the brief is unproven — each has a known solution shape that a competent
engineer can implement and, more importantly, can *demonstrate*. The concerns are not about
whether the system can be built. They are about budget: at ~20 hours/week over 10–12 weeks
the project has roughly **200–240 hours total**, and the brief's proof artifacts (Section 9)
are the most under-estimated line item in it. Test harnesses, chaos tooling and a
ground-truth simulator routinely cost more than the production code they validate.

Per the session rules, the response to that squeeze is to **cut features, never
architectural depth**. This document names which features go first, and does not propose
collapsing the event-driven core, the service boundaries, or any invariant.

One genuine scope trap is identified below that the brief does not flag: **road-geometry
interpolation in the simulator** silently imports a routing-engine dependency. Section
"Complexity Reduction" proposes a cheaper way to get the same fidelity.

---

## The Hard Part

Every project has one thing that is genuinely difficult and several that merely feel
difficult. It is worth being precise about which is which here, because the brief's framing
invites the wrong answer.

**The hard part is not the mechanisms. It is the evidence.**

Each individual challenge in Section 3 of the brief has a well-known solution that is small
in code:

- An atomic claim is a conditional `UPDATE ... WHERE status = 'AVAILABLE'` with an
  affected-row check, or a Lua script on Redis. Twenty lines.
- A durable timeout is a persisted `expires_at` column plus a sweeper query. Thirty lines.
- Consumer-side dedup is a `processed_messages` table with a unique key. Twenty lines.
- A transactional outbox is a table, a publisher loop, and a `sent_at` column.

None of that is where 240 hours goes. The hours go into being able to **prove** those
twenty lines are correct, reproducibly, in front of a stranger reading a README:

1. A **deterministic, seeded simulator** that emits ground truth independently of the system
   under test — so validation is not self-referential. This is the single largest component
   in the project and it produces no user-facing feature.
2. A **contention harness** that can reliably reproduce the double-assignment race. Races
   that occur naturally at 50 orders/min occur roughly never in a test run; the harness has
   to manufacture contention deliberately (many orders, few couriers, tight radius) and
   still be fast enough to run in CI.
3. **Chaos tooling** — Toxiproxy in the integration path, service kills mid-operation,
   assertions about state *after* recovery rather than during. Recovery assertions are
   harder to write than steady-state ones because the correct answer is a set of acceptable
   states, not one state.
4. **Invariant counters wired to Prometheus**, so a violation is observable at runtime and
   not merely caught by a test that happened to be written.

The second hard part, lower in severity but real: **operating nine moving parts solo**.
Postgres, Redis, Kafka, four services, Prometheus, Grafana, plus Toxiproxy and k6 in the
test path. Every one is a thing that can be misconfigured at 11pm. The mitigation is not
fewer parts — the brief correctly forbids that — it is that `docker compose up` must be
genuinely one command from a cold clone, enforced by CI, from Sprint 1 onward. If bringing
the stack up is ever a manual ritual, the project loses hours every week to friction.

What is **not** hard, and should not be allowed to consume time: the map UI, the REST
surface, the domain model itself (orders and couriers are a simple domain), and Kafka
topic design.

---

## Technical Feasibility

| Capability | Assessment | Basis | Confidence |
|---|---|---|---|
| Atomic courier claim (INV-1) | Solved problem | Conditional update with affected-row check is standard; Postgres row locks or Redis Lua both work | High |
| Order-side claim (INV-2) | Solved problem | Same mechanism, other side of the relation | High |
| Durable offer timeout | Standard but non-trivial | Persisted deadline + sweeper is well understood; the accept-vs-expire race needs care but is a single atomic transition | High |
| Accept/expire race convergence | Standard but non-trivial | Both paths must funnel through one conditional state transition on `offer.status`; whoever loses observes zero rows affected | High |
| Geospatial candidate search | Solved problem | Redis `GEOSEARCH` and PostGIS `ST_DWithin` are both mature; the choice is a tradeoff, not a risk | High |
| Location write amplification | Solved problem | Hot/cold split with batched flush is a standard pattern; 100 msg/s is modest | High |
| WebSocket fan-out across instances | Standard but non-trivial | Redis Pub/Sub fan-out is well documented; reconnection semantics need explicit design | Medium |
| Saga + compensation | Standard but non-trivial | Orchestrated saga over a small, explicit state machine; the risk is modelling sloppiness, not mechanism | Medium |
| Exactly-once effects on at-least-once | Solved problem | Inbox table with idempotency key; expiry policy is the only design question | High |
| Transactional outbox | Solved problem | Polling publisher is trivial; CDC is heavier and a separate decision | High |
| Deterministic seeded simulator | Standard but non-trivial | Prior work included a calibrated synthetic generator — direct transferable experience | Medium |
| **Road-geometry movement in simulator** | **Genuinely hard (as specified)** | Interpolating along real OSM roads implies map-matching or a routing engine (OSRM/GraphHopper) — a substantial dependency the brief does not account for | **Low** |
| Chaos testing with Toxiproxy | Standard but non-trivial | Toxiproxy is simple; the difficulty is asserting on post-recovery state | Medium |
| Reconciliation job | Solved problem | Prior work included exactly this against an append-only event log | High |
| Observability stack | Solved problem | OTel + Prometheus + Grafana via Compose is well-trodden | High |
| Kafka operations | Standard but non-trivial | Named as a learning target; Redpanda locally removes ZooKeeper/KRaft operational burden entirely | Medium |

The single **Low** confidence row is the only genuine unknown, and it is in the simulator,
not in the distributed core. That is a good position to be in — it means the risk sits in a
component that can be degraded without touching any invariant.

---

## Business Feasibility

This is a portfolio and skills-development project, so the economic question is not revenue.
It is: **does 200–240 hours invested in one artifact return more than the same hours spread
across three smaller ones?**

The honest answer is *yes, conditionally*. The condition is legibility. A hiring engineer
spends five to ten minutes on a repository. Depth that is not visible in that window has
zero value regardless of how real it is. This has direct consequences for prioritisation:

- The **README as hiring artifact** (Section 9.6 of the brief) is not documentation, it is
  the primary deliverable's user interface. It deserves genuine sprint capacity, not a
  final-evening scramble.
- **Measured numbers beat claimed patterns.** "p99 assignment latency 340ms at 50 orders/min
  with 300 couriers, measured, here's the k6 output" is worth more than a paragraph
  describing the saga pattern. The brief is right to insist that missed targets get
  published too — a published miss reads as engineering maturity, a suspicious absence
  reads as a gap.
- **The bug story matters disproportionately.** Section 9.6 asks for a written account of a
  real bug that chaos or concurrency testing caught. This is the highest-signal paragraph in
  the whole repository, because it is the one thing that cannot be produced by copying a
  reference architecture. Capture bugs as they happen, in a running log, from Sprint 2 — do
  not try to reconstruct one at the end.

The cost side: ~240 hours at any reasonable valuation is a significant investment, and it is
concentrated. The mitigation against total loss is that the project is **useful in stages**
— a working walking skeleton with a proven atomic claim is already a demonstrable artifact
at the end of Sprint 2, and every sprint after that increases value without risking what
came before. The sprint structure in Section 0.3 is doing real risk work, not just planning.

Against doing nothing: the three named learning targets — Kafka operations, distributed
locking, WebSocket scaling — are common gaps and are hard to acquire from reading. This is a
defensible way to acquire them.

---

## MVP Feasibility

**Thinnest useful version:** order intake → geospatial candidate search → offer to one
courier with a durable deadline → accept or expire → assignment persisted → invariants INV-1,
INV-2 and INV-3 enforced and proven under a contention harness. Backed by Postgres and Redis,
with Kafka carrying the order-created and assignment-made events. No map, no WebSocket, no
saga, no simulator beyond a crude load driver.

**Why this is the right cut:** it contains the entire thesis of the project in miniature.
Atomic claim under contention *is* the headline. Everything after it — durability, sagas,
real-time fan-out — is depth added to a claim already proven. If the project were abandoned
here it would still be a legitimate demonstration of the core skill.

**What it deliberately omits and why that is survivable:**

- **Live map and WebSocket** — the most demonstrable feature and the least technically
  interesting one. Withholding it is deliberate, and the brief already mandates this
  ordering. A map over a broken assignment core is a worse artifact than no map at all.
- **Saga compensation** — cancellation is a second-order concern until assignment works.
- **The full simulator** — a crude driver proves contention; realism is for Sprint 4.
- **Observability stack** — logs suffice to debug the first two sprints. Invariant counters
  arrive with the invariants they count.

---

## Scope Realism

Budget: **10–12 weeks × 20 h/week = 200–240 hours.** Rough allocation against the brief's
demands, at an AI-assisted throughput assumption:

| Area | Estimate (h) | Note |
|---|---:|---|
| Infrastructure, Compose, CI, project skeleton | 25 | Front-loaded; pays back weekly |
| Domain + assignment core (4 services) | 45 | The actual product code, and the smallest line item |
| Durability: outbox, inbox, sweeper, saga | 35 | Where the correctness work concentrates |
| Simulator (deterministic, ground-truth emitting) | 35 | Largest single component; see risk T-1 |
| Real-time: WebSocket, Redis fan-out, hot path | 25 | |
| Frontend (MapLibre, minimal) | 15 | Hard cap — see risk T-4 |
| Observability: OTel, Prometheus, Grafana, counters | 20 | |
| Test suites: concurrency, chaos, load, reconciliation | 40 | Consistently under-estimated |
| README, diagrams, demo script, writing | 20 | Primary deliverable's UI; do not compress |
| **Total** | **260** | |

**260 against a 200–240 budget.** The mismatch is roughly 10–25% — close enough to be
manageable, far enough that it will not resolve itself. Two honest options, and the brief
has already chosen between them:

- *Cut architectural depth* — forbidden by the session rules, and correctly so.
- **Cut features** — the correct lever. Named cuts, in the order they should be taken:
  1. **Merchant/customer-facing REST surface beyond the minimum.** One order-creation
     endpoint and one status endpoint. No CRUD completeness.
  2. **Grafana dashboard polish.** Three panels that matter (assignment latency histogram,
     offer outcome rates, invariant counters) beat twelve that are decorative.
  3. **Frontend beyond one screen.** One map, courier dots, order pins, live movement. No
     routing lines, no filters, no admin views.
  4. **Simulator behavioural realism** — keep the response distribution and the Poisson
     arrivals (they drive contention and load shape, which are load-bearing), degrade the
     movement model (see below).

That ordering is deliberate: each cut removes visible surface while leaving every invariant
and every proof artifact intact.

---

## Technical Risks

| # | Risk | Impact | Early signal | Mitigation |
|---|---|---|---|---|
| T-1 | **Road-geometry interpolation pulls in a routing engine.** "Interpolating along real OSM road geometry" as written implies map-matching or an OSRM/GraphHopper dependency — a multi-day detour with its own Docker footprint, for a component that generates test data | High — could consume 20+ hours and add a heavyweight container to a stack already at nine parts | Sprint 4 planning; the moment "how do I get a route between two points" is asked | Degrade to a **precomputed road graph**: extract way geometry from a small OSM extract once, offline, into a node/edge JSON asset; simulator random-walks that graph. Movement follows real streets, no routing engine, no runtime dependency. Fidelity loss is negligible for load generation |
| T-2 | **Accept/expire race is modelled in two places** and the two implementations diverge under load | High — this is INV-3, one of the headline claims | Two code paths both writing `offer.status` | Force both through a single atomic conditional transition; the loser observes zero affected rows and takes the no-op branch. Design it once in Phase 5, test it explicitly in Sprint 3 |
| T-3 | **Chaos tests are flaky** and get disabled, silently removing the failure-correctness proof | High — Section 2's second claim evaporates | Any `@Disabled`, any retry wrapper on a chaos test | Assert on *converged* state after a bounded settle window, never on state during recovery. Chaos suite runs in a separate CI job that is allowed to be slow but never allowed to be skipped |
| T-4 | **Frontend scope creep.** The map is the most rewarding thing to build and the least valuable | Medium — steals hours from proof artifacts | Any commit touching CSS in a sprint whose goal is not the frontend | Hard 15-hour cap, one screen, ugly-but-clear. Ordering constraint in Section 0.3 already defends this — enforce it |
| T-5 | **Kafka operational learning curve**, named as a learning target and therefore a real unknown | Medium — consumer group rebalancing and offset semantics can burn days | Sprint 1 | Redpanda locally (single binary, no ZooKeeper/KRaft management). Keep partition counts and consumer groups minimal until Sprint 3 |
| T-6 | **Nine-container Compose stack becomes slow or fragile**, taxing every dev cycle | Medium — compounds weekly | Cold `up` exceeding ~90 s, or any manual step | Health checks with proper `depends_on` conditions; a `core` profile (Postgres, Redis, Redpanda, services) separate from an `observability` profile so the common case starts less |
| T-7 | **Invariant counters written but never exercised**, so a violation is theoretically observable and practically invisible | Medium — undermines the observability claim | Counters that only ever read zero | Deliberately inject a violation in a test and assert the counter increments. A counter never seen non-zero is untested code |
| T-8 | **Reconciliation job becomes self-referential** — checking derived state against the same writes that produced it | Medium — the proof proves nothing | Reconciliation importing service-layer code | Reconciliation reads only the raw event log and raw tables, via its own queries, sharing no domain code with the services |

---

## Business Risks

| # | Risk | Impact | Early signal | Mitigation |
|---|---|---|---|---|
| B-1 | **Depth is real but illegible** — a reader spends 6 minutes and leaves without seeing any of it | High — the entire investment returns nothing | README that opens with setup instructions instead of results | README leads with architecture diagram, the six invariants, and the measured numbers table. Setup goes below the fold. Treat it as a landing page |
| B-2 | **Project stalls at ~70%** with the interesting parts done and the proof artifacts missing | High — an unfinished portfolio piece is close to worthless | Sprint 4 slipping while Sprint 5 content is untouched | Each sprint ends demonstrable and shippable. If time runs out, the project ends at a sprint boundary in a defensible state rather than mid-flight |
| B-3 | **Hosted demo cannot fit €10/month** and the constraint quietly becomes "no demo" | Medium — reduces reach but not substance | Phase 10 costing | Anticipated below; the reduced-profile-plus-local-full-stack path is the expected answer, decided properly in Phase 10 |
| B-4 | **The bug story never materialises** because no notable bug was recorded when it happened | Medium — loses the highest-signal content in the repo | End of Sprint 3 with an empty bug log | Keep `docs/bug-log.md` from Sprint 2. Cheap insurance; write entries the day they occur |

---

## Cost Concerns

Order of magnitude only; Phase 10 does this properly.

**Local development: €0.** The entire stack is open-source and Compose-hosted. OpenStreetMap
tiles are free at demo volume, and the brief's choice of MapLibre over a commercial SDK
already removes the one recurring cost this class of project usually carries.

**Hosted demo: the €10/month ceiling is very tight for this stack.** A directional read —
the full system is four JVM services plus Kafka, Postgres, Redis, Prometheus and Grafana.
Memory alone is roughly 3–4 GB for a comfortable full deployment. At mid-2026 pricing, 4 GB
of always-on VPS is €15–25/month; managed Kafka starts well above the entire budget. What
*does* fit in €10 is a single small VPS (2 GB, ~€5–8) running a reduced profile.

This is a real constraint that shapes what the hosted demo can be, not a blocker — the
project's requirement is that it runs locally via `docker compose up`, and that costs
nothing. Phase 10 decides the reduced hosted profile and states plainly what it drops.

**Nothing here scales with usage.** No per-token AI spend, no per-transaction fees, no SMS,
no metered map tiles, negligible egress. This is a fixed-cost project — unusual and
favourable.

**The real cost is hours, not euros**, and the Scope Realism section above is the honest
accounting.

---

## Timeline Concerns

- **No external dependencies with their own clocks.** No app store review, no compliance
  certification, no partner API access, no hardware lead time. Everything is
  self-contained — a significant and underrated advantage.
- **Sequencing risk is the main timeline risk**, and the brief has already addressed it well.
  The ordering constraint (correctness before visual) is the single most valuable planning
  decision in the document, because the map is what pulls effort forward out of sequence.
- **The one long-lead item is the OSM road-graph asset** (see T-1). Extracting and
  preprocessing it is a discrete, self-contained task that can be done any time from Sprint 1
  onward. Doing it early removes the Sprint 4 cliff.
- **Learning-curve time is unevenly distributed.** Kafka operations and WebSocket scaling are
  both new. Expect Sprint 1 and Sprint 4 to run slower than their content suggests, and
  budget accordingly rather than discovering it.
- **A 12-week plan with 5 × 2-week sprints leaves ~2 weeks of slack.** That slack is not
  spare capacity, it is the buffer that absorbs the 260-vs-240 mismatch. Do not plan work
  into it.

---

## Complexity Reduction

Per the session rules, this section does **not** propose reducing architectural depth. Every
entry targets complexity that is decorative rather than load-bearing, judged by one test:
*name the failure it isolates or the scaling axis it unlocks.* Anything that passes stays,
whatever it costs.

| # | Current shape | Simpler alternative | What is given up |
|---|---|---|---|
| 1 | Simulator interpolates along real OSM road geometry, implying a routing engine | Preprocess a small OSM extract into a static node/edge graph asset; random-walk it with turn preference | Nothing meaningful. Couriers still follow real streets. No true origin-destination routing — irrelevant, since couriers are load generators, not deliveries being optimised. **Recommended** |
| 2 | CDC (Debezium + Kafka Connect) for the outbox→Kafka boundary | Polling publisher: a scheduled query over unsent outbox rows | CDC is more correct at the margins (no publisher lag, no polling interval) and is genuinely heavier — a Connect cluster is two more containers and a configuration surface. Both teach the outbox pattern; only CDC teaches Connect. **Deferred to Phase 5 as a real ADR**, not pre-empted here |
| 3 | `tracking-service` as a separate deployable | Merge into `dispatch-service` | **Do not take this cut.** It passes the test: tracking isolates a high-frequency write path (100 msg/s sustained) from the low-frequency correctness path, and unlocks an independent scaling axis. Isolating location ingest from assignment latency is exactly the failure it prevents. Listed here to record that it was tested and kept |
| 4 | Twelve-panel Grafana dashboards | Three panels: assignment latency histogram, offer outcome breakdown, invariant violation counters | Visual richness. The three that remain are the ones that appear in the README, and the README is the deliverable |
| 5 | Full REST CRUD across orders and couriers | Order creation, order status, courier availability toggle, courier location report. Nothing else | API completeness nobody will exercise. The simulator is the primary client and it needs four endpoints |
| 6 | Kafka topics for every domain event | Topics only where a genuine async boundary exists: order lifecycle, assignment lifecycle, location stream | Diagram impressiveness. A topic whose producer and consumer are the same service is decoration — flag any such topic in Phase 6 and merge it into a direct call |

Entry 3 is included deliberately. A complexity audit that only ever removes things is not an
audit, it is a bias — recording what survived scrutiny and why is what makes the surviving
complexity defensible to a reader.

---

## What Discovery Must Establish

The brief answers most of what a discovery interview would ask, so Phase 2 is largely a
record of what was already stated. These are the genuine remainders — all resolved
autonomously per the session rules, and each tagged `[AUTO]` in project memory.

| # | Question | Why it matters | Phase 2 round |
|---|---|---|---|
| 1 | Tunis or Sousse as the simulated city? | Determines the OSM extract, the bounding box, and the demo's visual density | Round 5 |
| 2 | Is the accept/expire race allowed to resolve *either* way, or must a late accept always lose? | Changes the state machine. "Deterministic" requires picking one | Round 3 |
| 3 | Redis geo index, PostGIS, or hybrid for candidate search? | Read/write ratio decides it; also the largest single latency contributor | Round 3 |
| 4 | Polling publisher or CDC for the outbox? | Two containers and a learning axis versus simplicity | Round 7 |
| 5 | Does the hosted demo need to exist at all, or is a recorded walkthrough plus local-run instructions sufficient? | Directly governs whether the €10 ceiling is a real constraint | Round 7 |
| 6 | Should location history be retained indefinitely, or is a bounded window sufficient? | Governs Postgres growth and the partitioning decision in Phase 7 | Round 6 |
| 7 | Any authentication at all, or is the demo open? | Threat model scope in Phase 9 | Round 4 |

---

## Summary for Phase 2

Proceed. The idea is technically sound, the risk sits in the simulator rather than the
distributed core, and the only structural concern is a 260-hour estimate against a 240-hour
budget — resolved by cutting feature surface in the stated order, never depth.

Sharpest things discovery should carry forward: the road-geometry trap (T-1), the
accept/expire determinism question, and an explicit decision on whether a hosted demo is
required at all.
