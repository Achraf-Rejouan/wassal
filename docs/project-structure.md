# Project Structure

**Phase:** 11 · **Date:** 2026-08-08

## Shape and why

**A Gradle multi-module monorepo, one module per deployable service, hexagonal layering
within each service.**

Microservices in separate repositories is the usual shape, and it is wrong here. There is one
developer, one release cadence, and no independent deployment requirement — separate
repositories would buy team autonomy nobody needs while making a cross-cutting change (an
event contract, a shared metric name) a four-repository coordination exercise. A monorepo
keeps the whole system reviewable in one clone, which matters directly for the Reader
(NFR-007: clone once, run once).

What is preserved from the microservice shape is what actually matters: **each service is an
independently buildable, independently runnable artifact with its own database schema and no
compile-time dependency on any sibling.** The only shared module is `contracts`, which
contains event schemas and nothing else. A shared `common-domain` module is deliberately
absent — it is the standard route to a distributed monolith, because once two services share
a domain class they can no longer change independently.

Within each service, **hexagonal layering** (`api` / `domain` / `infra`) rather than package-
by-feature: each service is already a single bounded context, so a feature split inside it
would produce packages of two classes. The layering is what carries the dependency rule that
protects the invariants.

---

## Tree

```
wassal/
├── README.md                       # The hiring artifact. Diagram, invariants, numbers, first
├── PROJECT_CONTEXT.md              # Phase 14 handoff — entry point for agents
├── docker-compose.yml              # core profile: proxy, 2x gateway, 3 services, pg, redis, redpanda
├── docker-compose.observability.yml
├── docker-compose.tools.yml        # simulator, ui, toxiproxy
├── docker-compose.hosted.yml       # exhibition-mode overlay (security A-4, A-6)
├── .env.example                    # local-only dev credentials, committed deliberately
├── settings.gradle.kts
├── build.gradle.kts                # Spotless, ArchUnit, common versions
├── gradle/libs.versions.toml       # version catalogue — single source of dependency versions
│
├── contracts/                      # THE ONLY shared compile-time module
│   └── src/main/java/dev/wassal/contracts/
│       ├── order/                  # OrderCreated, OrderDelivered, OrderCancelled …
│       ├── assignment/             # OfferCreated, OfferAccepted, AssignmentCancelled …
│       └── Topics.java             # topic names as constants
│
├── gateway/
│   └── src/main/java/dev/wassal/gateway/
│       ├── api/                    # REST controllers, WebSocket handlers, DTOs
│       ├── identity/               # asserted-identity resolution (A-03), swap point for JWT
│       ├── ratelimit/              # sliding window; refuses boot if disabled + public (A-5)
│       ├── subscription/           # WS session registry, Redis Pub/Sub bridge (FR-016)
│       └── config/
│
├── order-service/
│   └── src/main/java/dev/wassal/order/
│       ├── api/                    # controllers, request/response DTOs
│       ├── domain/                 # Order aggregate, state machine, ports — NO framework types
│       │   ├── model/              # Order, OrderStatus, OrderId
│       │   ├── port/               # OrderRepository, EventPublisher (interfaces)
│       │   └── service/            # OrderStateMachine, IdempotentOrderCreator
│       └── infra/
│           ├── persistence/        # OrderEntity, JPA repositories, mappers
│           ├── outbox/             # polling publisher (ADR-0006)
│           └── messaging/          # Kafka consumers with inbox dedup
│
├── dispatch-service/               # the heart — most complex module by a wide margin
│   └── src/main/java/dev/wassal/dispatch/
│       ├── api/
│       ├── domain/
│       │   ├── model/              # Offer, Assignment, Courier, ClaimResult
│       │   ├── port/               # CandidateFinder, CourierClaimPort, AvailabilitySet
│       │   └── service/
│       │       ├── AtomicClaimExecutor.java      # ADR-0004 — INV-1, INV-2, INV-3
│       │       ├── OfferLifecycle.java           # FR-007, FR-012
│       │       └── CancellationSaga.java         # ADR-0008 — steps + compensations
│       └── infra/
│           ├── persistence/
│           ├── sweeper/            # OfferExpirySweeper — ADR-0005, 250ms, SKIP LOCKED
│           ├── redis/              # RedisGeoCandidateFinder, RedisAvailabilitySet (F-3)
│           ├── outbox/
│           └── messaging/
│
├── tracking-service/
│   └── src/main/java/dev/wassal/tracking/
│       ├── api/
│       ├── domain/
│       └── infra/
│           ├── hotpath/            # Redis geo:couriers + hot:pos writes (FR-010)
│           ├── coldpath/           # batching flusher → location_history
│           └── fanout/             # Redis Pub/Sub publisher
│
├── simulator/                      # a client, never a component of the system under test
│   └── src/main/java/dev/wassal/simulator/
│       ├── roadgraph/              # loads the precomputed OSM node/edge asset (A-01)
│       ├── courier/                # movement, response behaviour (FR-018, FR-019)
│       ├── arrivals/               # Poisson process with evening peak
│       ├── groundtruth/            # independent JSONL sink (FR-020)
│       └── profile/                # standard.yaml, stress.yaml, load.yaml
│
├── reconciliation/                 # MUST NOT import any service module (FR-017, E-02)
│   └── src/main/java/dev/wassal/reconciliation/
│       ├── source/                 # PostgresSource, KafkaFromOffsetZeroSource, GroundTruthSource
│       ├── compare/                # three-way comparison (Phase 6 finding F-2)
│       └── report/
│
├── proof/                          # the deliverable, separated so it cannot be diluted
│   ├── concurrency/                # Inv1DoubleAssignmentTest, Inv3AcceptIdempotencyTest …
│   ├── chaos/                      # kill scenarios, Toxiproxy faults
│   └── load/                       # k6 scripts + report generator
│
├── ui/
│   └── src/                        # React + MapLibre, one screen
│       ├── map/
│       └── api/
│
├── infra/
│   ├── db/migration/               # Flyway: V1__schema.sql … (per-schema)
│   ├── grafana/dashboards/         # provisioned as code (FR-023)
│   ├── prometheus/
│   ├── caddy/                      # TLS + exhibition-mode routing rules
│   └── tools/build-road-graph/     # offline OSM extract → node/edge JSON (A-01)
│
├── assets/
│   └── tunis-road-graph.json       # committed build output, ~5-20MB
│
├── scripts/
│   └── gen-bug-log.sh              # git log Bug: trailers -> docs/bug-log.md (S1-14)
│
├── docs/                           # this planning tree
│   ├── 00-project-memory.md … decision-log.md
│   └── bug-log.md                  # GENERATED — never hand-edited (G-6, item 4)
│
└── .github/workflows/
    ├── ci.yml                      # build, unit, IT, ArchUnit, gitleaks, dep-check
    ├── proof.yml                   # chaos + load — slow, never skipped (R-4)
    └── cold-clone.yml              # weekly: fresh clone → compose up → health (NFR-007)
```

---

## Directory Responsibilities

### `contracts/`
**Owns:** event schemas crossing service boundaries, topic name constants.
**May import from:** nothing but the JDK and Jackson annotations.
**Must not import from:** any service module — that would invert the dependency and make
`contracts` unbuildable in isolation.
**Maps to:** the Kafka topics in `04-architecture.md`.
**Note:** this module is deliberately tiny. Every class added here becomes a coupling point
between services, so additions need justification. If something is needed by only one service,
it does not belong here.

### `*/domain/`
**Owns:** aggregates, state machines, value objects, port interfaces, domain events.
**May import from:** the JDK, `contracts`, its own `domain`.
**Must not import from:** `api`, `infra`, JPA, Spring Data, Kafka, Lettuce.
**Reason:** the state machines carry the invariants. If they require a database to
instantiate, they cannot be unit-tested exhaustively — and exhaustive state-machine testing is
what INV-2 and INV-4 rest on.
**Maps to:** the module boundaries table in `04-architecture.md`.

### `*/infra/`
**Owns:** JPA entities, repository implementations, Kafka producers/consumers, Redis
adapters, the sweeper, the outbox publisher.
**May import from:** `domain` (to implement its ports), `contracts`.
**Must not import from:** `api`, or any sibling service's package.

### `*/api/`
**Owns:** controllers, WebSocket handlers, request/response DTOs, exception mapping.
**May import from:** `domain`.
**Must not import from:** `infra` — a controller reaching a repository directly bypasses the
domain service where the invariant checks live.

### `reconciliation/`
**Owns:** three-source comparison and the variance report.
**May import from:** the JDK, JDBC, a raw Kafka consumer, Jackson. **`contracts` only for
deserialising event payloads.**
**Must not import from:** `order-service`, `dispatch-service`, `tracking-service` — **any**
package under them, including repositories and mappers.
**Reason:** this is the ArchUnit rule that keeps the proof from being circular (Phase 6
Blocker F-2, threat E-02). A reconciliation job sharing the repository layer validates that
the code agrees with itself.

### `simulator/`
**Owns:** RNG state, road graph, courier behaviour, ground-truth sink.
**May import from:** the JDK, an HTTP client, `contracts`.
**Must not import from:** any service module; must not connect to Postgres or Redis directly.
**Reason:** it must reach the system only through the public API, exactly as a real client
would. Direct database access would make FR-020's ground truth entangled with the state it is
meant to independently verify.

### `proof/`
**Owns:** the concurrency, chaos and load suites.
**May import from:** everything — it is a test module.
**Note:** separated from each service's own tests so that the proof artifacts are visible as a
first-class part of the tree rather than buried in `src/test`. A Reader looking for "where is
the evidence" finds a top-level directory named `proof`. That is a documentation decision as
much as a structural one.

---

## Architecture Mapping

| Phase 5 component | Directory | Notes |
|---|---|---|
| gateway | `gateway/` | Two container instances from one artifact (F-7) |
| order-service | `order-service/` | |
| dispatch-service | `dispatch-service/` | Largest module; owns the claim, sweeper and saga |
| tracking-service | `tracking-service/` | Hot and cold paths in separate packages |
| simulator | `simulator/` | Client, not component |
| reconciliation-job | `reconciliation/` | Import-isolated by ArchUnit |
| web-ui | `ui/` | |
| Kafka topics | `contracts/` | Schemas and topic constants |
| Observability | `infra/grafana`, `infra/prometheus` | Provisioned as code |
| Road-graph tool | `infra/tools/build-road-graph/` | Offline, build-time only |

---

## Where Things Go

The table that stops the structure decaying — it answers the question someone actually has
halfway through a task.

| Adding… | Goes in | Also update |
|---|---|---|
| New REST endpoint | `<service>/api/` controller + DTOs | `06-api-contract.md`, FR traceability, an `…IT` test |
| New domain event | `contracts/<aggregate>/` | `04-architecture.md` topic table, producer outbox write, **consumer dedup** |
| New entity/table | `domain/model/` + `infra/persistence/` + Flyway migration | `05-data-model.md`: entity, access patterns, **indexes** |
| New index | Flyway migration | `05-data-model.md` — **must trace to a listed access pattern or be deleted** |
| **New invariant** | `domain/service/` + DB constraint | `03-prd.md` invariants + traceability matrix, a `proof/concurrency` test, **a Prometheus counter** |
| New background job | `infra/` sub-package, own connection pool | `04-architecture.md` pool table (F-5), a lag metric |
| New Kafka consumer | `infra/messaging/` | **Inbox dedup is mandatory**, consumer group name, offset-commit-after-effect |
| New metric | Where it is measured | Grafana dashboard JSON if it belongs on the three panels |
| New config value | `application.yml` + `.env.example` | `WASSAL_` prefix, documented in README if operator-facing |
| New Compose service | `docker-compose*.yml` | **`mem_limit` is mandatory** (NFR-011), health check, and check the hosted overlay publishes no port |
| New simulator behaviour | `simulator/courier/` or `arrivals/` | Profile YAML, **verify determinism still holds** (NFR-008) |
| **A bug found by a proof suite** | Fix + regression test | **`docs/bug-log.md`, the same day** (G-6) |
| A significant technical decision | Code | **`decision-log.md` as an ADR**, index line in `00-project-memory.md` |
| **A bug worth reading about** | The fix commit's **`Bug:` / `Found-by:` / `Cause:` / `Fix:` trailer** | Nothing — `docs/bug-log.md` regenerates from it. **Never hand-edit the log** |
| **A task that hit 2× its estimate** | A recorded note and an explicit continue/cut/defer choice | An ADR if the design changed |

---

## Enforcement

An unenforced layout rule has a half-life of about a month, so each rule below names its
enforcer. Rules with no enforcer are listed honestly as unenforced.

| Rule | Enforced by | Fails |
|---|---|---|
| `domain` imports no JPA/Kafka/Redis/Spring Data | **ArchUnit** `LayeringArchTest` | Build |
| `api` does not import `infra` | **ArchUnit** | Build |
| No cross-service package imports | **ArchUnit** | Build |
| `reconciliation` imports no service package | **ArchUnit** `ReconciliationIsolationArchTest` | Build |
| `simulator` imports no service package | **ArchUnit** | Build |
| No infrastructure mocking in tests | **ArchUnit** — forbids `Mockito` on `DataSource`, Redis and Kafka types | Build |
| No string concatenation into queries | **ArchUnit** + Spotless custom rule | Build |
| Formatting | **Spotless** `spotlessCheck` | Build |
| No `@Disabled` in `proof/concurrency` or `proof/chaos` | **CI grep step** | Build |
| No infra `ports:` in the hosted overlay | **CI script** on `docker-compose.hosted.yml` | Build |
| Every Compose service has `mem_limit` | **CI script** | Build |
| No secrets committed | **gitleaks** | Build |
| Dependency vulnerabilities | **OWASP Dependency-Check**, fail on High | Build |
| Cold clone runs in one command | **Weekly `cold-clone.yml`** | Workflow |
| **`bug-log.md` is not stale** | **CI regenerates via `scripts/gen-bug-log.sh` and diffs** | Build |

**Unenforced, and therefore at risk** — stated plainly rather than assumed:

| Rule | Why it cannot be automated | Mitigation |
|---|---|---|
| "State transitions must be conditional updates, not read-then-write" | The distinction is semantic; a static rule would produce false positives on legitimate reads | **Review checklist item #1**, and the `proof/concurrency` suite catches the consequence even if the pattern slips through — which is the real safety net |
| "Every conditional `UPDATE` carries all its preconditions" | Same | Checklist #2; a missing predicate is caught by `Inv…Test` only if the corresponding test exists, so this one genuinely relies on discipline |
| "New index traces to an access pattern" | Requires reading a document | Checklist #11 |
| `bug-log.md` **content** — a missing `Bug:` trailer on a commit that deserved one | Nothing can detect an entry that was never written | Reduced, not eliminated. The trailer is now part of a commit message being written anyway rather than a separate document to remember, and review checklist item 16 asks the question at the moment the commit is made. **Stated honestly: this is still the weakest link, just a thinner one** |

The middle row is the honest weak point of this whole scheme: **a conditional update missing
one predicate is a silent semantic change that only a test specifically written for it will
catch.** That is precisely why FR-012 has a named test and why the review checklist puts it
second.
