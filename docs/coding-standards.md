# Coding Standards

**Phase:** 11 · **Date:** 2026-08-08
**Stack:** Java 21 (LTS), Spring Boot 3.x, Gradle Kotlin DSL, React 18 + TypeScript for the UI
(ADR-0002, F-06).
**Style baseline:** **Spotless with google-java-format (AOSP variant, 4-space indent)**, run
on `spotlessApply` and enforced by `spotlessCheck` in CI. Frontend: **Biome** for lint and
format. Configuration lives in `build.gradle.kts` and `biome.json`.

Everything below covers what the baseline does not decide. Formatting is not discussed
anywhere in this document because the formatter has already decided it — arguing about braces
is time not spent on the accept-vs-expire race.

> **The rule for this document:** if a standard can be enforced by a tool, it is a tool
> config line, not a paragraph. What remains in prose is the set of decisions no linter can
> check, and those are the ones that carry this project's correctness.

---

## Naming

| Element | Convention | Real example from this project |
|---|---|---|
| Package | lowercase, no underscores | `dev.wassal.dispatch.domain.offer` |
| Class | `PascalCase` | `OfferExpirySweeper`, `AtomicClaimExecutor` |
| Interface | `PascalCase`, **no `I` prefix** | `CandidateFinder`, not `ICandidateFinder` |
| Implementation | Name the *strategy*, not the interface + `Impl` | `RedisGeoCandidateFinder`, not `CandidateFinderImpl` |
| Method | `camelCase`, verb first | `claimCourierForOrder()`, `sweepExpiredOffers()` |
| Boolean method | `is`/`has`/`can` prefix | `isTerminal()`, `hasActiveAssignment()` |
| Boolean field | Positive form only — **never** `notAvailable` | `available`, `stale` |
| Constant | `UPPER_SNAKE` | `DEFAULT_OFFER_TTL`, `MAX_CANDIDATE_RADIUS_M` |
| Domain event | `AggregatePastTenseVerb` | `OfferAccepted`, `AssignmentCancelled`, `CourierReleased` |
| Command | `VerbAggregate` | `AcceptOffer`, `CancelAssignment` |
| REST DTO | `…Request` / `…Response` | `CreateOrderRequest`, `OrderStatusResponse` |
| Persistence entity | `…Entity` | `OfferEntity`, `AssignmentEntity` |
| Domain model | Bare noun — **no suffix** | `Offer`, `Assignment`, `Courier` |
| Repository | `…Repository` | `OfferRepository` |
| DB table | `snake_case`, **plural** | `offers`, `assignments`, `location_history` |
| DB column | `snake_case`, **singular** | `courier_id`, `expires_at` |
| Timestamp column | **always `…_at`** | `expires_at`, `terminal_at`, `responded_at` |
| Enum in DB | Postgres enum, `snake_case` type, `UPPER_SNAKE` values | `offer_status`, `'OFFERED'` |
| API path | plural, kebab-case | `/v1/offers/{id}/accept` |
| Env var | `WASSAL_` prefix, `UPPER_SNAKE` | `WASSAL_OFFER_TTL_SECONDS` |
| Metric | `wassal_` prefix, `_total`/`_seconds` suffix per Prometheus convention | `wassal_invariant_violation_total` |
| Test class | `…Test` unit, `…IT` integration | `OfferStateMachineTest`, `AtomicClaimIT` |
| Test method | `should…When…` | `shouldRejectAcceptWhenDeadlinePassed()` |

**Acronyms are treated as words**: `HttpClient`, `OsmRoadGraph`, `WsSessionRegistry` — never
`HTTPClient` or `OSMRoadGraph`. This is the boundary case that generates the most pointless
disagreement, so it is settled here.

**Plural tables, singular columns** is the mapping rule: `offers.courier_id`. The JPA entity
is singular (`OfferEntity` → `offers`), which is the standard Hibernate convention and needs
no `@Table` annotation beyond the name.

### One naming rule that carries meaning

`Entity` suffixes exist **only** on persistence classes, and domain classes never have one.
This is not decoration — it makes a layering violation visible at the call site. An
`OfferEntity` appearing in a service method signature is immediately wrong to the eye, before
any tool runs. Given that this project's module boundaries are load-bearing for its
invariants, making violations visually obvious is worth a suffix.

---

## Project Layout

Full tree in `project-structure.md`. The rule for where a new file goes is the table at the
end of that document — consult it rather than guessing, because the layout maps directly to
the Phase 5 module boundaries and a misplaced file erodes a boundary that an invariant
depends on.

---

## Git Workflow

**Branching: trunk-based on `main`, committing directly.** No feature branches, no PRs.

This is the right call *because of the specific situation*, not by default. The skill's
caution is that trunk-based needs feature flags and real CI to work. Here: there is exactly
one developer, so there is no coordination cost to amortise and no reviewer to wait for; CI is
genuinely enforcing (build, unit, Testcontainers integration on every push); and the
alternative — opening PRs to review your own code — is ceremony that produces no review. F-16
and C-7 fix this anyway.

**The consequence, stated plainly: CI is the only quality gate.** With no second pair of eyes,
a weak CI pipeline means no gate at all. That is why the checks below are non-negotiable and
why several of this project's correctness rules are ArchUnit tests rather than review items.

**Commit convention: Conventional Commits**, and this matters more than usual here. **The
commit history is itself a portfolio artifact** — a Reader evaluating engineering judgment may
well scroll it, and "fix stuff" 200 times reads badly regardless of what the code does.

```
feat(dispatch): atomic courier claim via conditional update

Replaces the read-then-write in OfferService with a single conditional
UPDATE guarded by a partial unique index. Loser of the race now observes
zero affected rows and returns 409 rather than throwing.

Closes INV-1 gap found by Inv1DoubleAssignmentTest.
```

```
fix(tracking): keep highest recorded_at on out-of-order position reports

Mobile networks reorder. Hot path was last-write-wins by arrival, which
let a stale position overwrite a fresh one. Now compares recorded_at.

Found by: chaos suite, latency injection profile. See bug-log.md#3.
```

Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`.
Scopes: `order`, `dispatch`, `tracking`, `gateway`, `sim`, `infra`, `ui`, `docs`.

**Commit size:** one logical change. If the subject line needs "and", split it.

**Protected `main` with required checks:** `spotlessCheck`, unit tests, integration tests
(Testcontainers), ArchUnit tests, `gitleaks`, OWASP Dependency-Check (fail on High). The
chaos and load suites run on a separate workflow — **allowed to be slow, never allowed to be
skipped** (risk R-4).

### Bug trailers — `bug-log.md` is generated, not written (item 4)

The bug log is the highest-signal artifact in the finished repository (G-6) and was previously
the weakest link in the plan, because every proposed mitigation was a form of *"be
disciplined."* It is now a **byproduct of a commit you were writing anyway.**

When a commit fixes a real bug, the message body carries a structured trailer:

```
fix(dispatch): compare deadlines against database time, not JVM clock

Bug: offers expired up to 400ms early on one of two sweeper instances
Found-by: Inv4NoStuckOrdersTest, two-instance profile
Cause: sweeper compared expires_at against System.currentTimeMillis();
       container clocks drifted, so each instance had its own idea of "now"
Fix: all deadline comparisons moved into SQL using now(); JVM time is no
     longer read anywhere on the expiry path
```

**What qualifies** — the trailer is for bugs worth reading about, not every fix:

| Qualifies | Does not |
|---|---|
| A test caught it — chaos, concurrency, integration | Typos, formatting, renames |
| It cost more than ~30 minutes to diagnose | Compile errors |
| It was a wrong *assumption*, not a wrong keystroke | Missing null check found immediately while writing the code |
| It would recur in a similar system | Dependency version bumps |
| A correctness property was violated | Anything caught before the code ran once |

The `Cause:` line is the one that matters and the one most likely to be written lazily. It
must state **the mechanism, not the symptom** — "container clocks drifted" is a cause, "expiry
fired early" is a symptom, and the symptom is already the `Bug:` line.

**Generation and enforcement:**

- `scripts/gen-bug-log.sh` parses `git log` for `Bug:` trailers and regenerates
  `docs/bug-log.md`. Built in Sprint 1 (`S1-14`) so it exists before there is anything to log.
- CI regenerates and **fails if the committed file differs** — the same pattern as a formatter
  check.
- **Be honest about what that check does and does not do.** It detects a *stale* log, which is
  the failure that actually happens. It cannot detect a *missing trailer* on a commit that
  should have had one. Nothing can. The residual risk is smaller than before but it is not
  zero, and claiming otherwise would be the kind of overclaim this project's threat model
  treats as an adversary.
- The Sprint 3 watch-list trigger still applies: an empty log by the end of Sprint 3 means the
  proof suites are not adversarial enough — a finding, not a success.

### The 2× rule (item 6)

> **Any task that reaches twice its estimate stops.** Write down which task it was, where the
> time went, and choose explicitly: continue with a revised estimate, cut scope on the task, or
> defer it. Record the choice — a trailer in the commit, a bug-log entry, or an ADR if the
> decision changes the design.

The delivery plan's hour estimates only stay useful if they are corrected **while the project
is running.** Without this rule the 238 h figure silently becomes fiction around week five and
nobody notices until January. `S2-05`, `S3-05` and `S4-07` are the named likely candidates —
all three sit on experience gaps and already carry a 1.75× multiplier, so hitting 2× on top of
that means the multiplier itself was wrong, which is information worth having early.

The accumulated record of what ran long is good README material in its own right.

---

## Layering

The dependency rule, enforced by ArchUnit rather than by hope:

```
api  →  domain  ←  infra
```

- **`api`** (controllers, DTOs, WebSocket handlers) may import `domain`. **Never** `infra`.
- **`domain`** (aggregates, state machines, ports, domain events) imports **nothing** from
  `api` or `infra`. It has no Spring annotations except `@Component` on domain services, and
  no JPA, Kafka, Redis or Jackson types anywhere.
- **`infra`** (JPA entities, repositories, Kafka producers/consumers, Redis adapters)
  implements ports declared in `domain`. It may import `domain`. **Never** `api`.

The rule that catches the most real violations: **`domain` may not import
`jakarta.persistence`, `org.springframework.data`, `org.apache.kafka` or
`io.lettuce`.** Once a JPA annotation lands on a domain class, the state machine becomes
untestable without a database and the boundary is gone.

```java
// domain — port
public interface CourierClaimPort {
    ClaimResult claim(CourierId courier, OrderId order, OfferId offer);
}

// infra — adapter
@Repository
class PostgresCourierClaimAdapter implements CourierClaimPort { … }
```

**Cross-service imports are forbidden absolutely.** `dispatch-service` may not import from
`order-service`, even for a shared DTO. Shared contracts live in `contracts/` (see
`project-structure.md`) and are the only shared compile-time dependency. Two services sharing
a domain class is how a distributed monolith is built by accident.

---

## DTOs and Boundaries

**Three shapes, never fewer:**

| Shape | Lives in | Crosses | Example |
|---|---|---|---|
| Request/Response DTO | `api` | The wire | `AcceptOfferResponse` |
| Domain model | `domain` | Nothing — it is the centre | `Offer` |
| Persistence entity | `infra` | The database | `OfferEntity` |

**A persistence entity is never returned from a controller, and a request DTO never reaches a
repository.** The reason is concrete rather than dogmatic: returning `OfferEntity` would make
a column rename a breaking API change, and this project's API has a documented stability
contract on its error codes and field names (Phase 8) that the schema does not share.

Mapping is **hand-written** in explicit mapper classes, not MapStruct or reflection. At this
schema size the annotation processor's build cost and debugging opacity outweigh the typing
saved, and a mapper is exactly the place a silent field-drop bug hides.

**Java records for all DTOs and value objects**, classes only where mutable state or JPA
requires it (JPA entities need a no-arg constructor, so they are classes).

**Typed IDs, not bare UUIDs**: `CourierId`, `OrderId`, `OfferId` as records wrapping a
`UUID`. This is worth the ceremony in *this* system specifically — `claim(UUID, UUID, UUID)`
is a signature where swapping two arguments compiles cleanly and produces a wrong assignment.
`claim(CourierId, OrderId, OfferId)` cannot be called wrongly. Given that INV-1 and INV-2 are
the project's headline claims, making their arguments unswappable is cheap insurance.

---

## Validation

**Jakarta Bean Validation** on request DTOs at the edge; **domain invariants in constructors**.

| Where | What | Example |
|---|---|---|
| `api`, Bean Validation | Shape: required, ranges, lengths, patterns | `@NotNull`, `@Size(max=255)` on `Idempotency-Key` |
| `api`, custom validator | Cross-field and bounded context | Coordinates inside the Tunis bounding box → `ORDER_OUT_OF_BOUNDS` |
| `domain`, constructor | Invariants that must hold for the object to exist | `Offer` refuses construction if `expiresAt <= offeredAt` |
| **Database, constraints** | **Invariants that must hold regardless of code** | Partial unique indexes for INV-1/INV-2, `chk_terminal_consistency` |

**The fourth row is the one that matters.** Validation in code protects against mistakes;
constraints in the database protect against *bugs*, including future ones. Where an invariant
can be expressed as a constraint, it goes in the schema too — belt and braces, deliberately.

A `MethodArgumentNotValidException` maps to the Phase 8 envelope via a single
`@RestControllerAdvice`, producing `422 VALIDATION_FAILED` with field-level `details`. There
is exactly one such advice class per service, and it is the only place an HTTP status is
chosen for an exception.

---

## Error Handling

**A sealed exception hierarchy rooted at `WassalException`**, each carrying the stable error
code from the Phase 8 contract:

```java
public sealed abstract class WassalException extends RuntimeException
    permits ConflictException, NotFoundException, ValidationException,
            ForbiddenException, GoneException, UnavailableException {
    public abstract ErrorCode code();
}
```

Sealed so that the exception-to-status mapping in `@RestControllerAdvice` is an exhaustive
switch the compiler checks — adding a new exception type without mapping it will not compile.
Given that error codes are part of the API contract (a client asserts on them), an unmapped
exception silently becoming a `500` would be a contract break that no test necessarily
catches.

**Rules:**

| Rule | Reason |
|---|---|
| **A lost claim is a return value, not an exception** — `ClaimResult.lost()` | Losing a race is the expected path under contention, not an exceptional one. Under the stress profile it is the *majority* outcome, and exceptions on the hot path would be both slow and semantically wrong |
| Never catch `Exception` broadly except at the outermost consumer boundary | Where it *is* caught (Kafka listener), it must log with correlation ID and route to DLQ after 3 attempts |
| **Never swallow.** An empty `catch` block fails review | |
| Retry only idempotent operations, exponential backoff with jitter, capped at 3 | Retrying a non-idempotent command is how duplicates are manufactured |
| Internal detail never reaches a client | No stack traces, no SQL, no upstream messages. Correlation ID only |
| **`@Transactional` may never span a network call** | A transaction held open across an HTTP or Kafka call ties a database connection to a remote timeout, and the pool budget in Phase 6 (F-5) has no room for it. This is the single most damaging easy mistake available in this codebase |

---

## Logging

**Structured JSON via Logback + `logstash-logback-encoder`.** Standard fields on every line:
`timestamp`, `level`, `service`, `correlationId`, `traceId`, `spanId`, `message`.

| Level | What belongs here | Example |
|---|---|---|
| `ERROR` | Something is broken and needs a human. **Every invariant violation.** Saga in `FAILED_NEEDS_ATTENTION` | `INV-1 violated: courier already has active assignment` |
| `WARN` | Degraded but handled. Rate limiting engaged, sweeper lag above threshold, Redis unavailable and failing closed, **rate limiting disabled** | `Sweeper lag 0.7s exceeds 0.5s threshold` |
| `INFO` | Significant state transitions and lifecycle. One line per assignment created or cancelled. **Not** one per position report | `Assignment created` |
| `DEBUG` | Development detail. Off in all profiles by default | Candidate list contents |
| `TRACE` | Never committed enabled | |

**Volume discipline:** at 100 msg/s, an `INFO` line per position report is 8.6 M lines/day
that drowns everything real. Position ingest logs at `DEBUG` only; its observability comes
from metrics, not logs. **Metrics for rates, logs for events, traces for causality** — a
line in the wrong category is a defect.

**Never logged:** the raw `X-Correlation-Id` header value (security A-1 — CRLF forges log
entries, and in a system whose output *is* evidence, forging log entries means fabricating
evidence); full request bodies; any header value unvalidated. There are no credentials, tokens
or PII in this system to leak, which is one benefit of A-03.

**Redaction mechanism:** a Logback `TurboFilter` rejects any event whose formatted message
matches the CRLF pattern, plus a `@JsonIgnore`-style masking converter on known-sensitive
fields. Enforcement is at the framework level rather than by remembering, because "do not log
X" as a written rule has a half-life of about a month.

---

## Testing

| Level | What | Framework | Location | Naming | Required for merge |
|---|---|---|---|---|---|
| Unit | State machines, domain invariants, mappers, scoring | JUnit 5 + AssertJ | `src/test/java` | `…Test` | **Yes** |
| Integration | Real Postgres, Redis, Redpanda | JUnit 5 + **Testcontainers** | `src/test/java` | `…IT` | **Yes** |
| Architecture | Layering, boundaries, forbidden imports | **ArchUnit** | `src/test/java/arch` | `…ArchTest` | **Yes** |
| **Concurrency** | Contended claims, invariant assertions | JUnit + Testcontainers + executor pools | `:proof:concurrency` | `Inv…Test` | **Yes** |
| **Chaos** | Service kills, network faults | Testcontainers + **Toxiproxy** | `:proof:chaos` | `…ChaosTest` | Separate workflow — slow, **never skipped** |
| Load | Throughput and latency measurement | **k6** | `:proof:load` | `*.k6.js` | Separate workflow |
| Frontend | Component behaviour | Vitest | `ui/src` | `*.test.tsx` | Yes |

**Mocking rule: mock what you own the interface to; use the real thing for what you do not.**
So domain ports are mocked freely in unit tests; **Postgres, Redis and Kafka are never
mocked**, at any level. An embedded or mocked Redis would not exhibit the single-threaded
command semantics the geo index depends on, and a mocked Postgres cannot demonstrate that a
partial unique index rejects a second active assignment — which is the entire proof of INV-1.
This is an ArchUnit-enforced rule (NFR-009), not a preference.

**Test data:** builders with sensible defaults (`OfferBuilder.anOffer().expiringIn(15,
SECONDS)`), never shared fixtures. Each test creates what it needs and asserts on what it
created. Shared fixtures produce tests that pass because of another test's data.

**Database in tests:** one Testcontainers Postgres per module, reused across the class via
`@Container static`, with schema migrated by Flyway on start and **truncation between tests**
rather than rollback — because the claim path's behaviour under commit is exactly what is
being tested, and a rolled-back transaction never exercises the unique index.

**Three rules specific to this project's proof obligations:**

1. **`@Disabled` on any test in `:proof:concurrency` or `:proof:chaos` fails the build.**
   Enforced by a CI grep. Silencing a proof requires deleting it visibly, in a commit, in a
   public repository. (E-03.)
2. **A concurrency test must assert that contention occurred** — `assertThat(failedClaims)
   .isGreaterThan(0)`. A test where the race never happened is not a passing test, it is an
   unexecuted one. (E-04, FR-021.)
3. **Every invariant counter must be observed non-zero at least once** in the suite, via
   deliberate violation injection. A counter never seen firing is untested code. (R-8, FR-015.)

---

## Documentation

- **Javadoc required** on: public domain ports, anything implementing an invariant, and every
  non-obvious concurrency decision. Not required on getters, DTOs or obvious methods.
- **Any conditional `UPDATE` carrying an invariant gets a comment naming the invariant.**
  `-- INV-1: only one active assignment per courier`. Someone will eventually "simplify" that
  `WHERE` clause, and the comment is what stops them.
- **ADRs** go in `docs/decision-log.md`, append-only. Write one for anything painful to
  reverse. Do not write one for a library choice.
- **Same-PR updates:** a schema change updates `05-data-model.md`; an endpoint change updates
  `06-api-contract.md`; a new invariant updates `03-prd.md` and the traceability matrix. Docs
  drifting from code is how a planning artifact becomes a liability.
- **`bug-log.md`** — every defect found by the proof suites, the day it is found.

---

## Code Review Checklist

Self-review before pushing, since there is no second reviewer (C-7). Items are specific to
this project's architecture and threat model — a generic checklist gets skimmed.

**Correctness — the ones that carry invariants**

1. Does any state transition use **read-then-write**? It must be a single conditional `UPDATE`
   with an affected-row check. This is the project's central defect class.
2. Does the conditional `UPDATE` include **every** precondition — status, ownership *and*
   `expires_at` where applicable? A missing predicate is a silent semantic change (FR-012).
3. Does a new state change write its **outbox row in the same transaction**? (INV-5.)
4. Does a new Kafka consumer **deduplicate on `message_id`** before acting? (FR-014.)
5. Is every new compensating step **idempotent and safe to retry**? (FR-009, INV-6.)
6. Does a new invariant have **both** a test and a Prometheus counter? Either alone is
   insufficient.

**Boundaries and safety**

7. Does `@Transactional` span a network call anywhere? (Pool exhaustion — Phase 6 F-5.)
8. Does `domain` import JPA, Kafka, Redis or Spring Data? (ArchUnit should catch it; check
   anyway.)
9. Does a fetch-by-ID include its **ownership predicate in the query**, not after it?
   (Security A01.)
10. Is any query unbounded — no `LIMIT`, no pagination — on a table that grows?
11. Does a new index trace to a **listed access pattern** in `05-data-model.md`? If not,
    delete it or add the pattern.

**Operational**

12. Does a new Compose service declare a `mem_limit`? (NFR-011 is tight — F-6.)
13. Does the hosted overlay publish any infrastructure port? (Security A-4 — highest-impact
    hosted threat.)
14. Does a new failure path **fail closed**? No path may grant an assignment when it cannot
    verify the precondition (NFR-012).
15. Is a `@Disabled` being added to a proof test? If so, stop.
16. Does this commit fix a bug that qualifies for a `Bug:` trailer? If so, write it now —
    `gen-bug-log.sh` cannot invent it later.
17. Did any task in this batch hit 2× its estimate without being recorded?
