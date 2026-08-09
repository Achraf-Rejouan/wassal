# Infrastructure and Cost

**Phase:** 10 · **Date:** 2026-08-08
**Prices gathered:** 2026-08-08 via web search. **Verify before committing** — Hetzner made
a substantial price adjustment on 15 June 2026 and secondary sources still disagree on some
post-adjustment figures. Links to the authoritative pages are given at the end.

---

## Correcting the Phase 1 estimate

Phase 1 gave a directional read that the €10/month ceiling was "very tight" and that "4 GB
of always-on VPS is €15–25/month", concluding that only a reduced profile would fit.

**That read was too pessimistic and is now corrected.** Current pricing puts an 8 GB / 4 vCPU
instance at roughly €6.80/month, which fits the full stack — including the simulator and the
observability tier — inside the budget with room to spare. The Phase 1 figure appears to have
been anchored on managed-platform pricing rather than raw VPS pricing.

This is recorded rather than silently overwritten because the feasibility document is
explicitly a directional read that Phase 13 re-tests, and a corrected estimate in the
favourable direction is worth as much visibility as one in the unfavourable direction.

**Consequence:** the "reduced hosted profile" the brief anticipated (F-18) turns out to be
unnecessary. The recommendation below hosts the **full** stack.

---

## What actually has to run

From Phase 6's memory budget as amended (review F-6), plus Phase 9's exhibition mode (A-6):

| Component | Memory | Note |
|---|---:|---|
| gateway × 2 + reverse proxy | 800 MB | Two instances are required, not optional — they are what makes cross-instance fan-out observable (F-7) |
| order / dispatch / tracking services | 1 344 MB | |
| simulator | 512 MB | **Runs server-side in exhibition mode** — it is the only thing generating load |
| postgres | 768 MB | |
| redis | 256 MB | |
| redpanda | 1 024 MB | Largest single container |
| otel + prometheus + grafana | 900 MB | |
| **Total** | **≈ 5.6 GB** | Plus ~500 MB host OS → **~6.1 GB working set** |

Disk: ~15 GB for images, ~10 GB for 7 days of `location_history` at the demo's reduced
courier count, ~5 GB for Kafka and Prometheus retention. **~30–40 GB** is comfortable.

CPU: the workload is bursty and light — 300 simulated couriers at 100 msg/s is not CPU-bound.
2 vCPU suffices; 4 gives headroom for six JVMs starting simultaneously, which is the actual
peak (cold start, NFR-007).

Egress: **negligible**. No media, no file downloads, map tiles served by OSM directly to the
visitor's browser rather than proxied. This project has none of the egress exposure that
usually breaks a small hosting budget.

---

## Shortlist

| Provider | Fits? | Why / why not |
|---|---|---|
| **AWS** | No | ~6 GB always-on compute plus managed services is far past €10. Free tier expires and does not cover this shape. Highest operational overhead of any option |
| **Azure** | No | Same, and the smallest instances that fit are above budget |
| **Google Cloud** | No | Same. Cloud Run does not suit six always-on stateful processes |
| **DigitalOcean** | Marginal | Cleanest small-VPS UX, but an 8 GB droplet is roughly €45/month — 4.5× the ceiling |
| **Hetzner** | **Yes** | 8 GB / 4 vCPU at ~€6.80/month. EU-based, 20 TB traffic included. Cheapest per unit of compute by a wide margin | 
| **Fly.io** | Marginal | Genuinely good at long-running processes and WebSockets, but six machines plus volumes plus a managed Postgres lands well above €10 |
| **Railway** | No | Usage-based; six always-on services would run €25–40. Optimises for shipping speed, which is not the binding constraint here |
| **Render** | No | Free tier sleeps — fatal for a demo that must be live when a Reader clicks. Paid instances are ~€6 *each* |
| **Vercel** | **No — architecturally wrong** | Excellent for the React frontend, but this backend is six always-on stateful JVM processes with Kafka consumers, a 250 ms sweeper loop and persistent WebSocket fan-out. That is the opposite of the request-scoped model Vercel is built around |
| **Supabase** | No | Managed Postgres with auth and storage attached — collapses components this project deliberately keeps separate, and provides nothing for Kafka or Redis |
| **Oracle Cloud Free Tier** | **Worth taking forward** | 4 ARM cores / 24 GB RAM always-free is genuinely the only €0 option that could run this |
| **Self-host (home machine + tunnel)** | Worth taking forward | €0, full control |
| **No hosted demo** | **Worth taking forward** | The honest baseline. Q-05 asked whether a hosted demo is required at all |

**Taken forward:** Hetzner CX32 (cheapest option that fits comfortably), Oracle Cloud Free
Tier (the €0 candidate), self-hosting with a tunnel (the other €0 candidate), and no hosted
demo (the baseline that must be beaten to justify any spend).

---

## Comparison

### Hetzner Cloud — CX32

| Component | Service | Tier | Monthly |
|---|---|---|---|
| All containers | CX32 VPS — 4 vCPU, 8 GB, 80 GB NVMe | Shared vCPU | **€6.80** |
| IPv4 address | Included | | €0 |
| Traffic | 20 TB included | | €0 |
| TLS certificates | Let's Encrypt via Caddy | | €0 |
| Domain | `.dev` or `.app`, ~€12/year | | €1.00 |
| Backups | **Not taken** — state is regenerable from a seed | | €0 |
| **Total** | | | **≈ €7.80/month** |

**Cost by scale tier** (tiers from `architecture-review.md`, re-based on couriers):

| Couriers | Instance | Monthly | Note |
|---|---|---|---|
| 300 (target) | CX32 | **€7.80** | Comfortable |
| 3 000 | CX32 | €7.80 | Same box; ingest is not the constraint |
| 30 000 | CX42-class + separate Redis | ~€35 | Architecture needs sharding here anyway (Phase 6) |
| 300 000 | Out of scope | — | Non-goal |

**Scalability:** vertical resize is a reboot; horizontal needs real orchestration the project
does not want.
**Operational complexity:** ~1 h/month in steady state — OS updates, certificate renewal
(automatic), disk monitoring. Plus ~3 h one-time setup.
**Pros:** fits the full stack inside budget; EU-based; no egress risk; nothing proprietary.
**Cons:** you own the operating system. No managed backups (irrelevant here). Prices rose
sharply in June 2026 and could again.
**Lock-in:** **none.** Plain Docker Compose on a plain VPS. Moving is `scp` plus a DNS change.

### Oracle Cloud Free Tier — ARM Ampere

| Component | Tier | Monthly |
|---|---|---|
| 4 ARM cores, 24 GB RAM, 200 GB block storage | Always Free | **€0** |
| Domain | | €1.00 |
| **Total** | | **€1.00/month** |

**Scalability:** generous headroom — 24 GB is 4× what the stack needs.
**Operational complexity:** ~2 h/month, higher than Hetzner. The console is
harder, and the well-documented risk is that **always-free ARM capacity is frequently
unavailable in a given region**, sometimes for weeks.
**Pros:** genuinely free, and far more RAM than needed.
**Cons:** ARM64 means every image must have an ARM build — Redpanda, PostGIS, the
observability stack and all JVM images do, but it is a real compatibility surface to verify.
Free-tier instances have been reclaimed for inactivity in the past. **A demo that might
vanish is worse than no demo**, because the README links to it.
**Lock-in:** none technically; the availability risk is the real cost.

### Self-hosted with a tunnel (Cloudflare Tunnel / Tailscale Funnel)

**Total: €1.00/month** (domain only).
**Pros:** €0 compute, hardware already owned, full control.
**Cons:** requires a machine that is always on. A laptop is not. Home broadband upstream and
ISP terms become the demo's SLA, and a Reader clicking a dead link forms exactly the wrong
impression. **This is the option that looks free and is not** — it trades money for
reliability at the precise moment reliability matters most.

### No hosted demo — the baseline

**Total: €0.**
Deliverables instead: a recorded walkthrough (asciinema or a short screen capture) embedded
in the README, plus the `docker compose up` instructions that are already a hard requirement
(NFR-007).
**Pros:** zero cost, zero ops, nothing to break, nothing to monitor. Cannot go down before an
interview.
**Cons:** a Reader must clone and run to see anything live. Realistically, most will not.
**This is the option every other one has to beat.**

---

## Total Cost of Ownership

Ops hours valued conservatively — for a solo developer on a 240-hour budget, an hour of
operations is an hour not spent on proof artifacts, which is the scarcest resource in the
project.

| Option | Infra €/mo | Setup (one-time) | Ops h/month | 3-month TCO (€ + hours) | Risk |
|---|---:|---:|---:|---|---|
| **Hetzner CX32** | €7.80 | 3 h | 1 h | **€23 + 6 h** | Low |
| Oracle Free Tier | €1.00 | 6 h | 2 h | €3 + 12 h | **Medium** — capacity and reclamation |
| Self-host + tunnel | €1.00 | 4 h | 2 h | €3 + 10 h | **High** — availability |
| No hosted demo | €0 | 2 h (recording) | 0 h | **€0 + 2 h** | None |

**The money is not the deciding factor — every option is affordable.** The deciding factors
are hours and reliability, and read that way the table says something different from what the
euro column suggests: Oracle's free tier costs **twice as many hours** as Hetzner over three
months and carries a capacity risk, so it is the more expensive option in the currency that
is actually scarce.

---

## Recommendation

**Chosen: Hetzner CX32 running the full stack in exhibition mode — but deferred to Sprint 5,
with the recorded walkthrough built first as the guaranteed deliverable.**

That is two decisions, and they should be read together.

**On the provider:** Hetzner is the only option that runs the complete architecture — both
gateways, the simulator, and the observability tier — inside the budget without compromise,
at ~€7.80/month all-in. Nothing is dropped. There is no reduced profile, no missing
component, and no asterisk in the README saying "the hosted version omits X". Given that the
demo exists to make the architecture legible (R-10), a hosted version missing pieces of that
architecture would undercut its own purpose.

Oracle's free tier is tempting and is the right answer for someone optimising euros. It is
the wrong answer here because it costs six extra hours over three months and carries a real
risk of the instance being unavailable or reclaimed — and a dead link in a portfolio README
is actively worse than no link.

**On the timing:** the hosted demo is the **lowest value-per-hour item in the project**. The
README, the measured numbers and the bug story all rank above it for the Reader (Phase 3's
R-10 analysis), and none of them requires hosting. Standing up a VPS in Sprint 1 would spend
scarce hours on infrastructure for a system that does not yet do anything worth showing.

So: **the recorded walkthrough is the committed deliverable** — it satisfies the Reader's
five-minute window at 2 hours' cost and cannot break. The hosted demo is a Sprint 5 item,
taken only if the sprint has capacity. This is exactly the feature-cut ladder from
`feasibility.md` applied honestly: infrastructure polish is cut before proof artifacts.

**Cost at launch:** €0 (recorded walkthrough only).
**Cost if the hosted demo ships:** ~€7.80/month, ~€94/year.
**Cost at the 3 000-courier tier:** unchanged, €7.80.

**Reconsider when:**
- *The demo is needed live for a specific interview* → provision it; 3 hours from decision to
  running, because the Compose overlay is already specified.
- *Monthly spend would exceed €15* → the budget was €10; drifting past it silently is how a
  free-tier project becomes a subscription nobody remembers.
- *The hosted instance requires more than 2 hours in any month* → shut it down and fall back
  to the recording. It is not worth ops hours from a 240-hour budget.
- *Public interaction becomes desirable* → exhibition mode's read-only surface is the thing
  being given up, and real authentication (~12 h + a container) becomes mandatory first.

**Migration path if outgrown:** trivial, and this is the strongest argument for Hetzner.
Everything is plain Docker Compose on a plain Linux host with nothing proprietary anywhere —
no managed database, no vendor SDK, no platform-specific configuration. Moving to any other
provider is copying a directory and changing a DNS record. **The hosting decision is close to
free to reverse**, which is precisely why it does not deserve more analysis than it has
received.

---

## Costs People Forget — checked

| Line item | This project |
|---|---|
| **Egress** | 20 TB included; actual usage negligible. Map tiles go from OSM to the browser, not through the server |
| **Managed database** | **None used** — Postgres runs in a container. Would have been the largest line item |
| **Backups and snapshots** | **Deliberately none.** All state regenerates from a seeded run. €0, and it is a genuine property rather than a corner cut |
| **Non-production environments** | **None** (A-06). No staging bill |
| **Observability** | **Self-hosted** Prometheus and Grafana. Hosted log ingestion is a classic budget shock and is entirely avoided |
| **Third-party services** | **None.** No auth provider, email, SMS, payments, error tracking or CDN. This is the single biggest reason the budget holds |
| **AI inference** | **None** (A-08). The most volatile line on any modern estimate, and it is zero |
| **Support plans** | None |
| **Load balancer / NAT / static IP** | IPv4 included; the reverse proxy is a container, not a managed load balancer |
| **Domain** | ~€12/year — the only recurring cost besides compute |
| **Security controls** | **€0** (Phase 9) — exhibition mode and unpublished ports are decisions, not products |

**Nothing on this list scales with usage.** No per-token, per-transaction, per-message or
per-GB-ingested pricing anywhere in the system. That is unusual and it means the estimate
cannot be wrong by an order of magnitude in the way these estimates usually are.

---

## Sources

Prices gathered 2026-08-08. Verify before committing money — the June 2026 adjustment is
recent enough that secondary sources still disagree.

- [Hetzner Cloud — cost-optimized plans](https://www.hetzner.com/cloud/cost-optimized/)
- [Hetzner — new shared vCPU cloud servers](https://www.hetzner.com/pressroom/new-cx-plans/)
- [Hetzner — price adjustment, 15 June 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)
- [Hetzner — standardization and price adjustment detail](https://www.hetzner.com/pressroom/standardization-and-price-adjustment-of-our-server-products/)
- [Northflank — Hetzner 2026 price increases, full breakdown](https://northflank.com/blog/hetzner-cloud-server-price-increases)
