# Engineering Review

**Phase:** 13 · **Date:** 2026-08-08
**Reviewed:** all 15 documents, read in order as though written by someone else.

---

# Decisions made without you

This is the table to review. Every decision below was made autonomously under session rule
0.1 because the brief did not settle it. Each row gives the choice, why, and what reversing it
costs — **reversal cost is the column that matters**, because it tells you which
disagreements are cheap to act on and which are not.

## Assumptions (`[AUTO]`)

| # | Decision | Why | Cost to reverse |
|---|---|---|---|
| **A-07** | **Expiry wins the accept-vs-expire race.** The deadline is authoritative; an accept arriving after it is rejected even if it reaches the database first | Honouring a late accept opens the window where the order has already been re-offered — precisely the INV-1 hazard. Makes the invariant defensible rather than the UX kind | **Very low.** One predicate in one `WHERE` clause, one acceptance criterion. The mechanism is identical either way |
| **A-03** | **No authentication.** Identity asserted via `X-Courier-Id` / `X-Merchant-Id`, unverified. Record-level *authorization* retained and tested | Auth is solved and demonstrates nothing here; ~12 h and a container from a budget already short | **Low.** One gateway filter + config. Identity is already resolved to a principal before domain code sees it, so no domain change |
| **A-01** | **Precomputed OSM road graph**, walked by the simulator, instead of a runtime routing engine | Identical fidelity for load generation; saves ~20 h and one heavyweight container. Top technical risk from Phase 1 | **Medium.** ~15–20 h to add OSRM/GraphHopper, plus a container against a tight memory budget |
| **A-11** | **Exhibition mode** for any hosted deployment — public surface is read-only (GET + WebSocket), simulator drives state server-side | With asserted identity, a public write surface is trivially compromisable by anyone with `curl`. Removing the surface costs €0 and makes the demo more stable | **Low**, but it forces A-03: public interaction requires real auth first |
| **A-04** | **Tunis**, ~8 × 8 km central bounding box | Denser OSM coverage than Sousse → better road-graph asset, better contention clustering, better demo density | **Very low.** Config value + a different extract; under an hour |
| **A-05** | **7-day** location-history retention via partition drop; Kafka 24 h | ~60 M rows exercises partitioning and the cold path without exhausting a laptop disk | **Very low.** Partition schedule + topic config |
| **A-06** | **Local + CI environments only.** No staging tier | Nothing user-facing to stage; a staging tier costs hours and proves nothing | **Very low** |
| **A-08** | **No AI/ML anywhere.** Deterministic distance-and-availability scoring | Ranking sophistication is explicitly not the subject, and **non-determinism would invalidate every published measurement** (NFR-008) | **Low** to add, but it would break reproducibility — the reason is stronger than the cost |
| **A-09** | **Resource target: full stack ≤ 6 GB, `core` ≤ 3 GB** (NFR-011) | The brief stated no target; derived from what a 16 GB laptop runs alongside an IDE | Low — but see A-10 |
| **A-10** | **The 3 GB `core` sub-target is recorded as missed** (measured ≈4.7 GB after the two-gateway change), not quietly restated | F-14 requires publishing misses. Silently moving a target is the behaviour this project exists to avoid | n/a — it is a disclosure, not a choice |
| **A-02** | Name stays **Wassal** | Arabic for "connect/deliver"; short, fits the domain, unclaimed in this space | **Trivial.** Find-and-replace before first commit |

## Architecture decisions (ADRs)

| ADR | Decision | Why | Cost to reverse |
|---|---|---|---|
| **0004** | **Atomic claim in Postgres** (conditional `UPDATE` + partial unique indexes), **not** a distributed lock | Every Redis-lock variant splits the claim from the assignment insert, so a crash between them orphans the courier. The indexes make INV-1/INV-2 *structural* — a service bug cannot violate them | **High — effectively one-way.** The partial unique indexes are load-bearing; moving the claim would downgrade the invariants to convention |
| **0008** | **Orchestrated saga** with persisted state, not choreography | Crash-mid-saga resumability is required (FR-009), and choreography cannot provide it without reconstructing distributed state | **High.** Moving to choreography loses resumability — the property that decided it |
| **0003** | **Hybrid: Redis GEO for the candidate index, PostGIS for history** | Write:read is ~40:1. PostGIS-only fails NFR-003 arithmetically, not marginally | **Low.** Hidden behind `findNearestAvailable(...)` |
| **0005** | **Durable expiry: persisted `expires_at` + 250 ms sweeper** with `SKIP LOCKED`, no leader election | FR-012 needs the deadline as a SQL predicate in the accept path, which eliminates Redis ZSET, Kafka delays and Quartz | **Low.** The durable column stays regardless |
| **0006** | **Polling outbox publisher** (100 ms); CDC deferred | **This is where your learning-target rule was overridden, deliberately.** CDC teaches more, but the stated target is *Kafka operations* — exercised identically either way — and Connect + registry is ~1 GB against a 6 GB cap | **Very low, by design.** The outbox schema is Debezium-router-compatible, so switching is config plus deleting the publisher |
| **0007** | **Redis Pub/Sub for positions, Kafka for domain events** | Positions are current-state with a ~3 s useful life; at-most-once is the *correct* guarantee. Kafka would mean per-instance consumer groups for disposable processes | **Very low.** Behind an interface |
| **0002** | Four services + simulator | Each boundary maps to a failure the chaos suite induces. **More** services would be worse — splitting courier from assignment state would break the atomic claim | High |
| **0009** | **Hetzner CX32 (~€7.80/mo), hosted demo deferred to Sprint 5 and optional**; recorded walkthrough is the committed deliverable | Hosting is the lowest value-per-hour item; the README and numbers outrank it | **Very low.** Plain Compose on plain Linux — migration is `scp` + DNS |
| **0001** | Depth is the deliverable; cut features, never architecture | Your session rule 0.2, recorded as a decision so it does not read as oversight | n/a |

## Design calls not covered above

| Decision | Why | Cost to reverse |
|---|---|---|
| **`UNIQUE(offer_id)` on assignments is unconditional**, not partial — one offer produces one assignment *ever* | Stronger than INV-3 requires. Forces cancellation to produce a *new* offer rather than reviving one, closing a re-acceptance path that would otherwise need application logic | Low — drop the constraint, add the logic |
| **REST, not gRPC internally** | gRPC is defensible, but internal calls become invisible to `curl` — and this author has three named experience gaps and will debug a lot | Low |
| **Trunk-based on `main`, no PRs** | One developer; PRs to review your own code are ceremony. **Consequence stated: CI is the only quality gate** | Trivial |
| **Coverage target on `domain` only (90%)**, no global number | A global target pushes effort into testing mappers to hit a percentage | Trivial |
| **Ground truth is a JSONL file, not a table** | Deliberately less convenient — sharing a database with the system under test is how a proof becomes entangled with what it proves | Trivial |
| **Typed IDs** (`CourierId`, `OrderId`, `OfferId`) | `claim(UUID, UUID, UUID)` compiles with arguments swapped and produces a wrong assignment | Low |

---

## Summary

15 documents, 9 ADRs, 23 FRs, 12 NFRs, 6 invariants, 5 sprints. **Eight findings: one
Blocker, four Major, two Minor, one Observation.** All Blocker and Major findings have been
fixed in the source documents.

The Blocker is a genuine design gap rather than a documentation slip: **exhibition mode as
specified in Phase 9 would have prevented the simulator from driving the hosted demo at all**,
producing a live site showing a permanently empty map.

---

## Findings

| # | Severity | Area | Finding | Impact | Resolution | Doc updated |
|---|---|---|---|---|---|---|
| **R-01** | **Blocker** | Security ↔ Cost | **Exhibition mode breaks its own demo.** Phase 9's A-6 refuses all mutating endpoints on the hosted profile, and Phase 10 has the simulator running server-side to generate load. But the simulator drives the system through `POST /couriers/{id}/location`, `/offers/{id}/accept` and `POST /orders` — all refused. The hosted demo would show an empty map forever | **Critical.** The hosted demo is inert; the failure is invisible until someone visits | **Refusal happens at the reverse proxy only.** The server-side simulator connects to `gateway` directly on the Compose network, bypassing the proxy entirely. Documented as an explicit topology rule, with a CI check that the simulator's base URL in the hosted overlay is the internal service name, never the public hostname | `04-architecture.md`, `07-security.md`, `06-api-contract.md` |
| **R-02** | **Major** | API ↔ Security | **The API contract contradicts security A-3 on actuator exposure.** Phase 8's authorization table grants "Anyone" read on `/actuator/health` and `/metrics`; Phase 9 restricts `/actuator/prometheus` to the observability network and closes everything else. Two documents, two answers | Medium — the permissive one would likely be implemented, since it is in the contract a developer reads | API contract corrected to match: `health` public, `prometheus` observability-network only, everything else closed. `/metrics` renamed to `/actuator/prometheus` throughout | `06-api-contract.md` |
| **R-03** | **Major** | API | **Exhibition mode is absent from the API contract.** A developer reading Phase 8 would not know that mutating endpoints behave differently in the hosted profile | Medium — the hosted deployment would be built wrong | Added a Deployment Profiles section stating which endpoints are available where, and that the difference is enforced at the proxy | `06-api-contract.md` |
| **R-04** | **Major** | Requirements | **NFR-011 states a `core` target of ≤ 3 GB that is already known to be missed** (measured ≈4.7 GB after the two-gateway change from review F-7). The PRD still presents it as a requirement | Medium — a requirement known to be unmeetable is worse than no requirement; it trains you to ignore the others | NFR-011 amended: full stack ≤ 6 GB retained as Must; `core` sub-target restated as ≤ 5 GB with the original 3 GB figure and the reason it moved recorded inline. A `minimal` profile is named as the route back to ~3.2 GB if it matters | `03-prd.md` |
| **R-05** | **Major** | Feasibility | **Phase 1's cost verdict is now wrong and was left standing.** It concluded the €10 ceiling could not host the full stack; Phase 10 found current pricing fits it at ~€7.80/mo. The skill requires re-testing the Phase 1 verdict against what was actually found rather than leaving it quietly wrong | Medium — `feasibility.md` is the first document a reader opens, and a stale pessimistic conclusion misrepresents the project | Amendment block added to `feasibility.md` correcting the cost read and confirming the rest of the verdict still holds. Estimate reconciled: Phase 1's top-down ~260 h vs Phase 12's bottom-up 245 h | `feasibility.md` |
| **R-06** | Minor | API ↔ Security | The global WebSocket cap (2 000/gateway, threat T-08) appears in Phase 9 but not in Phase 8's rate-limiting table | Low | Added to the rate-limit table | `06-api-contract.md` |
| **R-07** | Minor | Plan | Phase 1 estimated ~260 h; Phase 12's bottom-up total is 245 h. Neither is wrong, but the discrepancy is unexplained and invites the reader to think one is careless | Low | Reconciled in the `feasibility.md` amendment — the 15 h difference is the road-graph simplification (A-01) landing in the detailed plan | `feasibility.md` |
| **R-08** | Observation | Data | `geo:couriers` holds every courier's position including offline ones, and is never pruned. Harmless at 300; at the 30 000-courier tier it wastes memory and slows `GEOSEARCH` before the sharding remedy applies | Negligible now | No change. Recorded in the scale-ladder remedies: prune on `OFFLINE` transition when courier count exceeds ~10 000 | `architecture-review.md` |

---

## Traceability Checks

| Check | Result |
|---|---|
| Every Must-have FR appears in the backlog | **Pass** — verified in `08-delivery-plan.md`'s traceability table; no orphans |
| Every FR maps to a component | **Pass** |
| Every user-facing FR maps to an endpoint | **Pass**, with FR-011 and FR-012 correctly internal — flagged in Phase 8 as *the hardest work being the least API-visible*, which is why they carry named tests |
| Every component serves ≥ 1 requirement | **Pass** |
| Every endpoint traces to an FR | **Pass** — 12 endpoints, no scope creep |
| Every NFR has a verification method | **Pass** — all 12 appear in the testing strategy |
| Every Phase 7 access pattern has an index or a stated reason not to | **Pass** — 15 patterns; three deliberate omissions recorded with reasons |
| Every invariant has a test **and** a counter | **Pass** — traceability matrix in `03-prd.md` |

## Consistency Checks

| Check | Result |
|---|---|
| No document contradicts a locked decision | **Pass** after R-02 and R-03 |
| Entity names match across PRD, data model, API | **Pass** — `Order`, `Courier`, `Offer`, `Assignment` consistent throughout |
| Auth model consistent across Phases 5, 8, 9 | **Pass** after R-02 |
| Scale numbers identical everywhere | **Pass** — 300 couriers, 50 orders/min, 100 msg/s, 500 WS, p99 500 ms |
| Delivery-plan technology matches the ADRs | **Pass** |
| Every Accepted ADR reflected in the documents; none contradicts a later one | **Pass** — ADR-0003 carries a Phase 6 amendment note rather than being edited |
| `project-structure.md` matches Phase 5 module boundaries | **Pass** |
| Hosting choice matches the Phase 5 deployment section | **Pass** after R-01 |

## Completeness, Architecture, Security, Performance

| Area | Result |
|---|---|
| Every assumption confirmed or flagged | **Pass** — 11 `[AUTO]`, all in the table above |
| Every open question resolved or deferred with an owner | **Pass** — Q-01…Q-07 all closed |
| Error handling for every external dependency | **Pass** — degraded-modes table covers all six |
| Empty / loading / error / permission-denied states | **Pass** for the map. Note: no signup, reset, permission-change, deletion or export flows exist — **there are no accounts**, so the account-lifecycle checklist is genuinely inapplicable rather than skipped |
| Simplest design meeting the NFRs, or a documented reason otherwise | **Documented reason** — ADR-0001. The inversion is explicit, and Phase 6 confirmed nothing decorative survived |
| No SPOF contradicting the availability target | **Pass** — availability target is explicitly *none*; single-instance Postgres and Redpanda are consistent with it |
| Nothing beyond what the team can operate | **Pass, tightly.** Nine containers on one developer is the stated cost of ADR-0001, with the 15%-of-hours trigger as the abort signal |
| Cost compatible with budget | **Pass** — €0 local, ~€7.80/mo optional |
| **Phase 1 verdict still holds** | **Amended** — see R-05. Verdict remains *Proceed with concerns*; the cost concern is withdrawn, the scope concern is confirmed and now has concrete cuts |
| Every high-likelihood/high-impact threat mitigated or accepted | **Pass** — 17 conventional + 8 epistemic; four explicitly accepted |
| Object-level authorization on every fetch by ID | **Pass** — enforced as query predicates, not post-fetch checks |
| No secret in any example or diagram | **Pass** — `.env.example` holds local-only development values, deliberately, with the tradeoff stated |
| Each latency NFR traced to a design element | **Pass** — the budget decomposition in `04-architecture.md` accounts for all 500 ms with 220 ms headroom |
| No unbounded query | **Pass** — the only growing collection (variance report) is cursor-paginated; sweeper and publisher both `LIMIT` |
| N+1 patterns identified | **Pass** — candidate search returns IDs then one batch fetch; no lazy-loaded collection in a loop |
| Cache invalidation defined, not just population | **Pass** — every cache row states its invalidation *and its cost of staleness* |
| First bottleneck named with a trigger threshold | **Pass** — sweeper at 400/s (`sweeper_lag_seconds > 0.5`) and pool exhaustion (`hikari_connections_pending > 0` for 30 s) |
| Riskiest unknown addressed early | **Pass** — road-graph spike pulled into Sprint 1; atomic claim proven in Sprint 2 |
| Infrastructure and tooling work in the backlog | **Pass** — S1 is 45 h of it |
| Rollback defined | **Pass** — `git revert` + recreate; forward-only migrations justified |

---

## Unresolved

| # | Item | Why unresolved | Owner | Needed by |
|---|---|---|---|---|
| ~~U-1~~ | ~~Whether the hosted demo is actually wanted~~ | **Closed 2026-08-09 — dropped** (ADR-0010). The recorded walkthrough is the committed deliverable | — | — |
| ~~U-2~~ | ~~Whether the Redis-Lua claim comparison spike ships~~ | **Closed 2026-08-09 — scheduled**, not optional. `S2-12`, 4 h, timeboxed, immediately after `AtomicClaimExecutor` | — | Sprint 2 |
| U-3 | Real measured numbers | Cannot be known before the system exists. Every NFR figure is a *target*, not a measurement | Build | Sprint 5 |
| U-4 | Whether the road-graph asset is achievable in the 6 h timebox | Genuine unknown; the largest scope risk | Build | Sprint 1 |

U-3 deserves emphasis: **every performance number in this document set is a design target
that has never been measured.** The plan's value depends on Sprint 5 publishing what actually
happened, including targets that are missed.

---

## Residual Risks

Carried into implementation, accepted knowingly.

| Risk | Why accepted |
|---|---|
| ~~245 h against 200–240 h~~ → **313 h realistic at 13 h/week** | **Superseded 2026-08-09.** The gap was larger than this row stated — the plan's own arithmetic was wrong (Sprint 1 was 59 h, not 45 h) and velocity was overstated by 50%. Resolved by resetting the finish line to `v0.4.0` at 24 weeks (ADR-0010), not by cuts |
| **Nine containers on one developer** | The stated price of ADR-0001. Abort signal: operations exceeding 15% of hours |
| **Chaos tests being quietly disabled (E-03)** | Highest-likelihood silent failure. Mitigated by a CI grep, but ultimately depends on not deleting the check |
| **`bug-log.md` going unwritten (G-6)** | Nothing can detect an unwritten entry. The weakest link in the whole scheme, and named as such in `project-structure.md` |
| **Motivation decay at ~70% (R-1)** | Structural mitigation only: every sprint ends tagged and demonstrable, so stopping early yields a defensible artifact |
| **Two learning-target areas concentrated in Sprints 1 and 4** | Both sprints budgeted to run slow. Sprint 4 is the largest at 55 h and the most likely to overrun |
| Volumetric DDoS, non-repudiation, internal plaintext | Phase 9, accepted with reasons |

---

## Readiness Assessment

| Area | Ready? | Notes |
|---|---|---|
| Requirements | **Yes — re-triaged 2026-08-09** | 23 FRs, 12 NFRs, 6 invariants with tests and counters. **Must ratio 78%** (was 96%; that justification did not hold — see Changelog item 2) |
| Architecture | **Yes** | Reviewed separately in Phase 6; 9 findings applied. Latency budget decomposed |
| Data model | **Yes** | 11 tables, 15 access patterns, every index justified. Invariants realised as constraints |
| API contract | **Yes** after R-01…R-03, R-06 | 12 endpoints, traceability clean both ways |
| Security | **Yes** | Two adversaries modelled; 6 amendments applied upstream; posture costs €0 |
| Delivery plan | **Yes — reset 2026-08-09** | `v0.4.0` committed: 238 h nominal / 313 h realistic / 24 weeks to 24 Jan 2027. ~16 h of slack inside committed scope; stretch items named |
| Operational readiness | **Not applicable, deliberately** | No production, no on-call, no backups — each recorded as a decision |

---

## Sign-off

**Implementation can begin.** Start with Sprint 1's walking skeleton (`08-delivery-plan.md`).

Four documents are the contract an implementer works from — **`03-prd.md`,
`04-architecture.md`, `05-data-model.md`, `06-api-contract.md`**. The rest is context.
`PROJECT_CONTEXT.md` (Phase 14) is the entry point that ties them together.

**What must be settled before Sprint 4, not now:** whether the hosted demo is wanted (U-1),
and whether to take the three pre-emptive scope cuts.

**The one thing to watch from the first week:** `docs/bug-log.md`. It is the only artifact in
this plan that cannot be reconstructed later, and the highest-signal content in the finished
repository.


---

# Changelog — pre-Sprint-1 amendment pass (2026-08-09)

Six external review findings applied before any code is written. **Two produced a partial
dissent** (ADR-0011); one uncovered an arithmetic error in the plan being reviewed.

| Item | What changed | Documents touched |
|---|---|---|
| **1 — Timeline reset** | Velocity corrected to **13 h/week**. Selective unfamiliarity multiplier (×1.75 on 65 h of gap-touching work, ×1.15 elsewhere). Committed scope reset to **`v0.4.0`** — 238 h nominal, **313 h realistic, 24 weeks to 24 Jan 2027**. Sprint 5 split into committed core proof and stretch. Sprint plan re-expressed in **calendar weeks with dates and tags**. Sprints rebalanced to 49–52 h. All defensible cuts taken as baseline. **Discovered Sprint 1's items summed to 59 h against a 45 h headline — the plan's real total was 260 h, not 245 h** | `08-delivery-plan.md`, `feasibility.md` (2nd amendment), `decision-log.md` (ADR-0010, ADR-0011), `00-project-memory.md`, `PROJECT_CONTEXT.md` |
| **2 — Must-ratio re-triage** | FR Must ratio **96% → 78%** (18 of 23); NFR **92% → 75%** (9 of 12). Five FRs demoted: FR-003, FR-017, FR-019, FR-022, FR-023. Three NFRs demoted: NFR-002, NFR-006, NFR-011. Cut ladder rebuilt from genuinely demoted items — **≈35 h total, ≈16 h available inside committed scope**, against the previous claimed 15 h | `03-prd.md`, `08-delivery-plan.md` |
| **3 — Redis comparison promoted** | Moved from Sprint 5 could-have to **`S2-12`, Sprint 2, 4 h timeboxed**, immediately after `AtomicClaimExecutor`. Scoped as a *written comparison, not a shipped alternative*: Lua script in a test source set only, benchmarked under the contention profile, plus a step-by-step crash-window analysis. Result appends as an amendment under ADR-0004. **U-2 closed as scheduled** | `decision-log.md` (ADR-0004 amendment), `08-delivery-plan.md`, `00-project-memory.md` |
| **4 — `bug-log.md` automated** | Structured `Bug:` / `Found-by:` / `Cause:` / `Fix:` commit trailer with an explicit qualifies/does-not table. `scripts/gen-bug-log.sh` added as **`S1-14`** (2 h). CI regenerates and fails on drift. **Documented honestly that the check detects a stale log, not a missing trailer** — the residual risk is thinner, not gone | `coding-standards.md`, `project-structure.md`, `08-delivery-plan.md` |
| **5 — Memory targets provisional** | NFR-011 marked **PROVISIONAL**, with the environment assumption stated explicitly (**native Docker on Fedora, 16 GB host, no Desktop VM**). New task **`S1-15`** measures actual footprint and restates the targets; nine-container risk re-rated then. General principle recorded: *a target derived from an assumption about the environment is provisional until the environment is measured.* The 15%-of-hours abort signal is untouched — it concerns time, not memory | `03-prd.md`, `08-delivery-plan.md`, `00-project-memory.md` |
| **6 — 2× over-run rule** | Added to both `coding-standards.md` and the delivery plan's watch list. Names `S2-05`, `S3-05`, `S4-07` as likely candidates in advance. Review checklist items 16 and 17 added. Weekly-hours logging added to the watch list, since 13 h/week is itself an estimate (A-13) | `coding-standards.md`, `08-delivery-plan.md` |
| **7 — CDC premise** | No action, as instructed. ADR-0006 correctly rejected the brief's "CDC is more correct" premise; noted so it is not re-opened | — |

## Dissent recorded (ADR-0011)

The review instructed that all five named cuts be taken as baseline scope. **Four were taken;
two were struck as defective**, and the defects were inherited from the plan under review
rather than introduced by the reviewer:

- **Cut 3 — 300 → 100 couriers, "saves 3 h".** Saves approximately **zero** — the courier
  count is a configuration value, not work. Worse, 300 couriers reporting every 3 s *is* the
  100 msg/s of NFR-003; at 100 couriers ingest falls to 33 msg/s and the write-amplification
  demonstration loses its stated load. **Withdrawn entirely.**
- **Cut 5 — pre-emptive grid road graph, "saves 3 h".** `S1-12` is already a 6 h timebox whose
  documented fallback *is* the grid graph. Taking the cut now surrenders real street geometry —
  visible in the demo, part of FR-018's acceptance criteria — to save time the timebox already
  protects. **Rejected as a pre-emptive cut; retained as the existing conditional fallback.**

Consequence, stated plainly: **the original ladder was worth ~9 h, not 15 h.** That
*strengthens* the review's item 2 finding rather than weakening it — the ladder was thin
because prioritisation had been deferred, and two of its five entries were padding. The
re-triage in item 2 is what produced a usable ladder.

## Revised headline numbers

| | Before | After |
|---|---|---|
| Velocity | 20 h/week | **13 h/week** |
| Nominal hours | 245 h (really 260 h) | **238 h** committed + 20 h stretch |
| Realistic hours | not modelled | **313 h** committed |
| Committed scope | all 5 sprints | **`v0.4.0`** — Sprints 1–4 + core proof |
| Duration | 10–12 weeks | **24 weeks** |
| Finish date | — | **24 January 2027** |
| FR Must ratio | 96% | **78%** |
| Cut ladder | 15 h claimed | **≈35 h total, ≈16 h inside committed scope** |
| Open questions | 2 (U-1, U-2) | **0** |
| ADRs | 9 | **11** |
