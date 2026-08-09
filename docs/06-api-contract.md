# API Contract

**Phase:** 8 · **Date:** 2026-08-08
**Derived from:** `04-architecture.md` (as amended) and `05-data-model.md`.

---

## Style: REST, and why not the alternatives

**REST over HTTP/JSON**, with WebSocket for the live stream.

| Option | Assessed against *this* project | Verdict |
|---|---|---|
| **REST** | The primary client is the simulator, which needs four endpoints and machine-readable error codes to assert on. `curl` reproducibility matters directly — a Reader must be able to drive the system by hand from the README | **Chosen** |
| GraphQL | Clients need no varied shapes of a graph; there is one client and one screen. Would add query-cost analysis and depth limiting as new responsibilities for zero benefit | Rejected |
| gRPC internally | Genuinely defensible for gateway→service calls: both ends are ours and latency is budgeted. Rejected because it would make internal calls invisible to `curl` during debugging, on a system whose author has three named experience gaps and will be debugging a lot. **Cheap to add later**; the internal API is not public | Rejected for now |

No ADR is opened for this. REST here is the conventional default serving a single simulator
client, and it is trivially reversible — an ADR for it would be noise that makes ADR-0004
and ADR-0006 harder to find.

---

## Conventions

| Aspect | Rule |
|---|---|
| Base URL | `http://localhost:8080/v1` |
| Versioning | URI path (`/v1`). See Versioning below |
| Content type | `application/json; charset=utf-8` |
| Naming | Plural nouns, kebab-case paths, `camelCase` JSON fields |
| Dates | **ISO 8601 with explicit offset, always UTC** — `2026-08-08T14:23:11.482Z`. Millisecond precision, because offer deadlines are reasoned about in sub-second terms and a truncated timestamp would make the accept-vs-expire race untraceable |
| IDs | UUID v4 as strings |
| Coordinates | `{ "lat": 36.8065, "lon": 10.1815 }`, WGS84 decimal degrees. Named fields rather than a GeoJSON array, because `[lon, lat]` ordering is the most reliably-mistaken convention in geospatial work |
| Pagination | Cursor-based; see below |
| Idempotency | `Idempotency-Key` header on every mutating request. **Max 255 chars, charset `[A-Za-z0-9_-]`**; `400 VALIDATION_FAILED` otherwise (security A-2 — an unbounded client-supplied primary-key component is a storage and index-bloat vector) |
| Correlation | `X-Correlation-Id` accepted and echoed; generated if absent; propagated across services and into Kafka headers (NFR-010). **Must be a valid UUID v4**; `400` otherwise, and the raw header value is never written to a log (security A-1 — CRLF in this header forges log entries, which in a system whose output *is* evidence means fabricating evidence) |

---

## Authentication

**None.** Identity is asserted, not verified (A-03).

```
X-Courier-Id:  <uuid>     # courier-scoped endpoints
X-Merchant-Id: <uuid>     # merchant-scoped endpoints
```

The gateway reads these headers and treats them as identity without any credential check. A
request may claim to be any courier.

This is a deliberate decision, not an oversight, and Phase 9 documents the threat model it
implies. The reasoning in short: authentication is a solved problem that demonstrates nothing
this project exists to demonstrate, and it would cost ~12 hours and a container out of a
budget already 20 hours short.

**Authorization is retained**, which is the part that matters. It is record-scoped rather
than role-based, and the distinction carries real correctness weight: "a courier may accept
only offers addressed to them" is not a security rule here so much as a **correctness** rule
that interacts directly with the claim path. A courier accepting someone else's offer would
be an INV-1 hazard, not merely a permissions violation.

### What production would require

Recorded so the omission reads as a decision. Adding real auth is a contained change: an OIDC
provider (Keycloak container), a JWT validation filter at the gateway, and `sub` replacing the
header as the identity source. **No domain code changes** — the authorization checks below
already operate on a resolved identity rather than on the header itself. That containment is
why the shortcut is safe to take.

---

## Authorization

| Role | Resource | Permitted | Constraint | Enforced where |
|---|---|---|---|---|
| Merchant | `POST /orders` | Create | — | Gateway |
| Merchant | `GET /orders/{id}` | Read | **Only own orders** (`order.merchant_id == X-Merchant-Id`) | `order-service`, in the query |
| Courier | `POST /couriers/{id}/availability` | Update | **Only own record** (`{id} == X-Courier-Id`) | Gateway |
| Courier | `POST /couriers/{id}/location` | Create | **Only own record** | Gateway |
| Courier | `POST /offers/{id}/accept` \| `/decline` | Update | **Only offers where `offer.courier_id == X-Courier-Id`** | `dispatch-service`, **inside the claim transaction** |
| Courier | `POST /assignments/{id}/pickup` \| `/deliver` \| `/cancel` | Update | **Only own assignment** | `dispatch-service`, in the conditional update |
| Anyone | `GET /orders/{id}/stream` (WS) | Subscribe | Order must exist | Gateway |
| Anyone | `/actuator/health` | Read | — | Service |
| Observability network only | `/actuator/prometheus` | Read | **Not public** — security A-3 | Proxy + service |
| Nobody | all other actuator endpoints (`env`, `heapdump`, `threaddump`) | — | Closed entirely (security A-3) | Service config |

**Two enforcement layers, deliberately.** Gateway checks are cheap and fail fast; service
checks are the ones that count. The offer-acceptance check in particular is enforced **inside
the claim transaction** rather than before it — as a predicate in the same conditional
`UPDATE`:

```sql
UPDATE dispatch.offers SET status = 'ACCEPTED'
 WHERE id = :offerId
   AND courier_id = :callerCourierId   -- authorization, atomically
   AND status = 'OFFERED'
   AND expires_at > now();
```

A separate authorization query followed by an update would be a check-then-act with a race
window — the same class of bug the whole project exists to avoid. Folding the ownership check
into the claim's `WHERE` clause means authorization and the invariant are enforced by one
atomic operation. Worth stating explicitly, because "authorize, then act" is the reflexive
pattern and here it would be wrong.

---

## Error Format

One envelope, everywhere:

```json
{
  "code": "COURIER_UNAVAILABLE",
  "message": "Courier is no longer available",
  "correlationId": "9f8c2e7a-1b3d-4c5e-8a9f-0d1e2f3a4b5c",
  "details": [
    { "field": "pickup.lat", "issue": "OUT_OF_BOUNDS", "value": 48.8566 }
  ]
}
```

`code` is a **stable machine-readable identifier and part of the contract** — the simulator
and the test suites assert on it. `message` is prose and may change freely. `details` appears
only for validation failures. Stack traces are never returned; they are logged against the
correlation ID.

### Error codes

| Code | HTTP | Meaning | Retryable |
|---|---|---|---|
| `VALIDATION_FAILED` | 422 | Field-level validation | No |
| `ORDER_OUT_OF_BOUNDS` | 422 | Coordinates outside the configured bounding box | No |
| `POSITION_REQUIRED` | 422 | Courier went available with no known position | No |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same key, different payload | No |
| `INVALID_STATE_TRANSITION` | 409 | Not permitted by the state machine | No |
| **`COURIER_UNAVAILABLE`** | **409** | **Lost the atomic claim.** The normal, expected outcome of a race | **No — see below** |
| `ORDER_ALREADY_ASSIGNED` | 409 | Lost the order-side claim (INV-2) | No |
| `COURIER_HAS_ACTIVE_ASSIGNMENT` | 409 | Cannot go available while holding an assignment | No |
| `ORDER_TERMINAL` | 409 | Order already in a terminal state | No |
| **`OFFER_EXPIRED`** | **410** | **Deadline passed.** Distinct from 409 by design | No |
| `NOT_ASSIGNED_COURIER` | 403 | Caller is not the assignee | No |
| `NOT_OFFER_RECIPIENT` | 403 | Offer addressed to another courier | No |
| `ORDER_NOT_FOUND` / `COURIER_NOT_FOUND` / `OFFER_NOT_FOUND` | 404 | Absent, or not yours — never distinguished | No |
| `SERVICE_UNAVAILABLE` | 503 | Infrastructure down; **failed closed** (NFR-012) | **Yes**, honour `Retry-After` |
| `UPSTREAM_TIMEOUT` | 504 | Internal call timed out; failed closed | Yes |
| `INTERNAL_ERROR` | 500 | Unexpected | Maybe |

Two entries carry more weight than they appear to:

**`COURIER_UNAVAILABLE` is not an error condition — it is the system working.** Under the
stress profile it is the *majority* response, and the client must treat it as a definite,
final answer rather than something to retry. A client that retries into a claim it already
lost is the fastest route to violating INV-1. This is why it is `409` (definite conflict)
rather than `503` (try again), and why the response carries no `Retry-After`.

**`OFFER_EXPIRED` is `410 Gone`, not `409 Conflict`, and the distinction is semantic rather
than cosmetic.** 409 means "you lost a race against another actor"; 410 means "you lost a
race against time". The courier's client should surface these differently — one is "someone
beat you", the other is "you were too slow" — and separating them lets the simulator assert
on the accept-vs-expire race directly (FR-012) rather than inferring it.

---

## Status Codes

| Code | Meaning in this API | Example |
|---|---|---|
| 200 | Success; also idempotent replay of a prior creation | Repeat `POST /orders` with a used key |
| 201 | Created | First `POST /orders` |
| 202 | Accepted asynchronously | `POST /couriers/{id}/location` — queued to the hot path |
| 204 | Success, no body | `POST /offers/{id}/decline` |
| 400 | Malformed JSON or missing required header | Absent `Idempotency-Key` |
| 403 | Record-scoped authorization failure | Accepting another courier's offer |
| 404 | Not found, or not yours | |
| 409 | **Lost a race, or invalid transition** | Concurrent claim |
| 410 | **Deadline passed** | Late accept |
| 422 | Semantically invalid | Coordinates out of bounds |
| 429 | Rate limited | Location flood |
| 500 / 503 / 504 | Server, dependency down (fail closed), upstream timeout | |

---

## Endpoints

Twelve endpoints. The REST surface was deliberately capped at the minimum by the Phase 1
cut ladder — the simulator is the primary client and needs four of these.

### Orders

#### `POST /v1/orders`
**Purpose:** create a delivery order.
**Auth:** `X-Merchant-Id` required.
**Idempotency:** **required.** `Idempotency-Key` header; replay returns the original order.

```jsonc
// Request
{
  "pickup":  { "lat": 36.8065, "lon": 10.1815 },
  "dropoff": { "lat": 36.8189, "lon": 10.1658 },
  "pickupAddress": "Avenue Habib Bourguiba",   // optional, ≤ 200 chars
  "dropoffAddress": "Rue de Marseille"          // optional, ≤ 200 chars
}
```

**Validation:** `lat` ∈ [-90, 90], `lon` ∈ [-180, 180], both required; both points must fall
inside the configured Tunis bounding box or `ORDER_OUT_OF_BOUNDS`. Pickup may equal dropoff
(valid degenerate case).

**Responses:** `201` with the order body · `200` on idempotent replay · `409
IDEMPOTENCY_KEY_REUSED` · `422 VALIDATION_FAILED` / `ORDER_OUT_OF_BOUNDS` · `503`.

```jsonc
// 201
{
  "id": "…", "status": "PENDING",
  "pickup": {…}, "dropoff": {…},
  "createdAt": "2026-08-08T14:23:11.482Z",
  "slaDeadline": "2026-08-08T14:38:11.482Z"
}
```
**Serves:** FR-001, FR-014.

#### `GET /v1/orders/{id}`
**Purpose:** current order state, assignment, and last known courier position.
**Auth:** `X-Merchant-Id`; only own orders.
**Idempotency:** safe.

```jsonc
// 200
{
  "id": "…", "status": "ASSIGNED",
  "assignment": {
    "id": "…", "courierId": "…", "assignedAt": "2026-08-08T14:23:14.108Z"
  },
  "courierPosition": {
    "lat": 36.8071, "lon": 10.1809,
    "recordedAt": "2026-08-08T14:24:02.331Z",
    "stale": false
  },
  "offerAttempts": 2
}
```

`courierPosition` is `null` when unassigned or when no position has been reported.
**`stale: true`** when the position is older than 60 s — surfaced rather than suppressed,
because a demo that hides degraded data is a demo that lies about the system it is
demonstrating (FR-003).

**Responses:** `200` · `403` · `404 ORDER_NOT_FOUND`.
**Serves:** FR-003.

### Couriers

#### `POST /v1/couriers/{id}/availability`
**Purpose:** go online or offline.
**Auth:** `X-Courier-Id` must equal `{id}`.
**Idempotency:** naturally idempotent — setting the current state is a no-op returning `200`.

```jsonc
{ "available": true }
```

**Responses:** `200` · `403` · `409 COURIER_HAS_ACTIVE_ASSIGNMENT` (cannot go available while
assigned — guards INV-1 at the API boundary as well as at the claim) · `422 POSITION_REQUIRED`
(available with no known position) · `503`.
**Serves:** FR-005.

#### `POST /v1/couriers/{id}/location`
**Purpose:** report a position. The highest-volume endpoint at 100 req/s.
**Auth:** `X-Courier-Id` must equal `{id}`.
**Idempotency:** **inherent, not header-based** — `(courierId, recordedAt)` is the natural
key, and a duplicate is discarded by the primary key. No `Idempotency-Key` is required, since
requiring one at 100 req/s would add cost to the hot path for a guarantee the data model
already provides.

```jsonc
{ "lat": 36.8071, "lon": 10.1809, "recordedAt": "2026-08-08T14:24:02.331Z", "speedKmh": 22.4 }
```

**Responses:** `202 Accepted` (written to the hot path; the cold path is asynchronous) ·
`403` · `404 COURIER_NOT_FOUND` · `429` · `503` when Redis is down — **positions are dropped
and counted, never queued**, because a queue of stale positions is worse than no positions
(FR-010).

**Batch form:** `POST /v1/couriers/{id}/locations` accepts up to 50 positions in one request.
Not needed at 300 couriers; specified now because it is the 3 000-courier scaling remedy
identified in the Phase 6 review, and retrofitting a batch endpoint later would mean a
simulator change too.

**Serves:** FR-010, NFR-003.

### Offers — the correctness-critical surface

#### `POST /v1/offers/{id}/accept`
**Purpose:** accept an offer, atomically claiming courier and order.
**Auth:** `X-Courier-Id` must match `offer.courier_id` — **enforced inside the claim
transaction**, not before it.
**Idempotency:** **required.** Replay returns the original assignment with `200`. This is
INV-3, and it is the single most important idempotency guarantee in the API.

**Responses:**

| Code | Body | When |
|---|---|---|
| `201` | assignment | Claim won |
| `200` | assignment | Idempotent replay of a won claim |
| `409 COURIER_UNAVAILABLE` | error | **Lost the courier-side race.** Expected and common under stress |
| `409 ORDER_ALREADY_ASSIGNED` | error | Lost the order-side race (INV-2) |
| **`410 OFFER_EXPIRED`** | error | **Deadline passed — including when the accept reached the database first.** Expiry is authoritative (A-07) |
| `403 NOT_OFFER_RECIPIENT` | error | Offer belongs to another courier |
| `404 OFFER_NOT_FOUND` | error | |
| `503` | error | Postgres unavailable — **fails closed**, no assignment created |

```jsonc
// 201
{
  "assignmentId": "…", "orderId": "…", "courierId": "…",
  "status": "ACTIVE", "assignedAt": "2026-08-08T14:23:14.108Z",
  "pickup": {…}, "dropoff": {…}
}
```

**Serves:** FR-008, FR-012, FR-014 · **INV-1, INV-2, INV-3.**

#### `POST /v1/offers/{id}/decline`
**Purpose:** decline; triggers the next candidate within 200 ms (in-process, no Kafka round
trip — review F-8).
**Idempotency:** naturally idempotent.
**Responses:** `204` · `403` · `404` · `410 OFFER_EXPIRED` · `409 INVALID_STATE_TRANSITION`
if already accepted.
**Serves:** FR-007.

### Assignments

#### `POST /v1/assignments/{id}/pickup` · `/deliver` · `/cancel`
**Auth:** `X-Courier-Id` must be the assignee.
**Idempotency:** required on all three; replay is a no-op returning `200`.

| Endpoint | Transition | Notable failures |
|---|---|---|
| `/pickup` | `ASSIGNED → PICKED_UP` | `409 INVALID_STATE_TRANSITION` |
| `/deliver` | `PICKED_UP → DELIVERED` | `409` if pickup was skipped — the machine has no shortcut. Releases the courier **exactly once** (INV-6) |
| `/cancel` | `ASSIGNED\|PICKED_UP → CANCELLED` | `409 ORDER_TERMINAL` if already delivered. Starts the compensating saga (FR-009) |

`/cancel` body: `{ "reason": "VEHICLE_BREAKDOWN" }` — free text, ≤ 200 chars, recorded for
the report but not interpreted.

**Serves:** FR-004, FR-009 · **INV-6.**

### Live stream

#### `GET /v1/orders/{id}/stream` — WebSocket upgrade
**Purpose:** live courier position for one order.
**Auth:** none beyond order existence.

Server → client frames:

```jsonc
{ "type": "position", "courierId": "…", "lat": 36.8071, "lon": 10.1809,
  "recordedAt": "…", "stale": false }

{ "type": "status", "orderId": "…", "status": "ASSIGNED", "assignmentId": "…" }

{ "type": "degraded", "reason": "FANOUT_UNAVAILABLE" }   // never silent stalling

{ "type": "terminal", "orderId": "…", "status": "DELIVERED" }  // server then closes
```

**Reconnect semantics — a deliberate design choice, not a simplification.** On resubscribe
the client receives the **current** position immediately; missed intermediate positions are
**not** replayed. Positions are current-state, not an event stream, and replaying them would
animate a courier retracing a path already travelled — visibly wrong, and more expensive.

**Backpressure:** per-connection bounded queue of 100 frames; on overflow the **oldest** is
dropped and `websocket_frames_dropped_total` increments. Dropping oldest rather than newest
is correct for current-state data — the freshest position is the only one that matters.

**Serves:** FR-016, FR-022 · NFR-006.

### Operational

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Compose health checks, with `readiness` and `liveness` groups |
| `GET /actuator/prometheus` | Metrics, including the six invariant counters. **Reachable from the observability network only** (security A-3) |
| `POST /v1/admin/reconcile` | Trigger the reconciliation job; returns a run ID |
| `GET /v1/admin/reconcile/{runId}` | Variance report (FR-017) |

Reconciliation is exposed as **an async job resource rather than a synchronous call**,
because a full three-source comparison takes minutes and no request should hang for it.

---

## FR ↔ Endpoint Traceability

Both directions checked. An endpoint serving no FR is scope creep; an FR with no endpoint is
a gap.

| FR | Endpoint(s) | |
|---|---|---|
| FR-001 Create order | `POST /orders` | ✓ |
| FR-002 State machine | *(internal — no endpoint)* | ✓ Correctly internal |
| FR-003 Query status | `GET /orders/{id}` | ✓ |
| FR-004 Complete delivery | `/assignments/{id}/pickup`, `/deliver` | ✓ |
| FR-005 Availability | `POST /couriers/{id}/availability` | ✓ |
| FR-006 Candidate search | *(internal)* | ✓ Correctly internal — no client chooses candidates |
| FR-007 Offer lifecycle | `/offers/{id}/decline` + internal | ✓ |
| FR-008 Atomic claim | `/offers/{id}/accept` | ✓ |
| FR-009 Cancellation saga | `/assignments/{id}/cancel` | ✓ |
| FR-010 Location ingest | `POST /couriers/{id}/location(s)` | ✓ |
| FR-011 Durable expiry | *(internal sweeper)* | ✓ |
| FR-012 Accept-vs-expire | `/offers/{id}/accept` → `410` | ✓ Observable via the distinct status code |
| FR-013 Outbox | *(internal)* | ✓ |
| FR-014 Idempotency | `Idempotency-Key` on all mutations | ✓ Cross-cutting |
| FR-015 Invariant counters | `/actuator/prometheus` | ✓ |
| FR-016 WebSocket stream | `GET /orders/{id}/stream` | ✓ |
| FR-017 Reconciliation | `/admin/reconcile` | ✓ |
| FR-018–021 Simulator | *(client, not server)* | ✓ Correctly absent |
| FR-022 Map | *(consumes the above)* | ✓ |
| FR-023 Dashboards | `/actuator/prometheus` | ✓ |

**No orphans in either direction.** Every endpoint traces to an FR, and every FR either has
an endpoint or is correctly internal. Note that FR-011 and FR-012 — durable expiry and the
race resolution, arguably the two most interesting requirements — have **no dedicated
endpoint**, being observable only through the `410` on accept and through metrics. That is
correct but worth flagging to Phase 12: *the hardest work in this system is the least visible
through its API*, so the tests and dashboards are the only place it can be demonstrated.

---

## Pagination

Only one endpoint could ever return a growing collection — the reconciliation variance report
— and it uses **cursor pagination**:

```
GET /v1/admin/reconcile/{runId}?cursor=<opaque>&limit=100
→ { "items": [...], "nextCursor": "…" | null }
```

Default limit 100, maximum 500. Cursor over `(entity_type, entity_id)`. Offset pagination is
rejected because the variance set can change between pages while the reader is walking it.

No other endpoint returns a collection — a direct consequence of the Phase 1 decision to cut
the REST surface to the minimum.

---

## Rate Limiting

| Tier | Limit | Window | Scope | On exceed |
|---|---|---|---|---|
| Location reports | **150/min per courier** | Sliding 60 s | `X-Courier-Id` | `429`, `Retry-After: 1` |
| Order creation | 300/min per merchant | Sliding 60 s | `X-Merchant-Id` | `429` |
| Offer responses | 60/min per courier | Sliding 60 s | `X-Courier-Id` | `429` |
| WebSocket connections | 10 concurrent per client IP | — | IP | `429` on upgrade |
| **WebSocket, global** | **2 000 concurrent per gateway instance** | — | Instance | `503` on upgrade. 4x the 500 target (security T-08) — file-descriptor exhaustion is the failure this prevents |
| Everything else | 600/min per identity | Sliding 60 s | Identity | `429` |

The location limit is set at **150/min against an expected 20/min** (one report per 3 s), a
7.5× margin. It exists to catch a **runaway simulator**, not to defend against an attacker —
there is no attacker, and a bug that makes the simulator report in a tight loop would
otherwise silently corrupt every measured number in the load report. Framing rate limits as a
correctness guard rather than a security control is the right reading for this system.

Limits are enforced at the gateway in Redis with a sliding-window counter, and they are
**disabled under the stress and load profiles** by configuration — otherwise the profile
designed to force contention would be throttled before contention occurs.

That flag is itself a hazard, and Phase 9 (threat T-06) required it be treated as one rather
than merely logged: **the gateway refuses to start** when rate limiting is disabled *and* the
public profile is active, and logs at `ERROR` in every case where limits are off. A config
mistake here would remove the only DoS control on a public deployment, and config mistakes
are the highest-likelihood risk class in this system (OWASP A05).

---

## Versioning

**Scheme:** URI path, `/v1`.

**Breaking changes** — removing a field, removing or renaming an **error code**, narrowing
validation, changing a status code for an existing condition, changing WebSocket frame shapes.
Note that error codes are explicitly part of the contract: the simulator asserts on them, so
renaming `COURIER_UNAVAILABLE` breaks clients exactly as removing a field would.

**Non-breaking** — adding an optional field, adding a new error code for a *new* condition,
adding an endpoint, relaxing validation.

**Deprecation policy:** not applicable in practice. Single client, single repository, solo
workflow (C-7); a breaking change is a coordinated commit. Recorded because "no versioning
policy" normally signals an oversight, and here it signals that there is no second party to
coordinate with. The `/v1` prefix costs nothing and preserves the option.

---

## Deployment Profiles

The API behaves differently depending on where it is deployed, and a developer reading only
this document would otherwise not know it. Added by review finding R-03.

| Endpoint class | Local profile | **Hosted profile (exhibition mode)** |
|---|---|---|
| `GET /v1/orders/{id}` | Available | Available |
| `GET /v1/orders/{id}/stream` (WS) | Available | Available |
| `GET /actuator/health` | Available | Available |
| **All `POST` endpoints** | Available | **Refused with `405` — at the reverse proxy** |
| `/actuator/prometheus` | Observability network | Observability network |

**Enforcement is at the reverse proxy, and that placement is load-bearing** (review finding
R-01). The gateway itself continues to accept mutations on the internal Compose network,
because the **server-side simulator must still drive the system** — it connects directly to
`http://gateway:8080`, bypassing the proxy entirely.

Had refusal been implemented inside the gateway, exhibition mode would have blocked the
simulator too, and the hosted demo would show a permanently empty map. That failure would not
surface until someone visited the site.

> **Rule:** in the hosted overlay, the simulator's base URL must be the internal service name
> (`gateway`), never the public hostname. A CI check asserts this.

---

## Webhooks

**None.** No third party receives callbacks; there is no runtime third-party integration
anywhere in the system. The simulator receives offers over Redis Pub/Sub rather than by
webhook, so no signing, replay protection or retry-with-backoff machinery is required — one
of several places where the absence of external integrations removes a whole subsystem.
