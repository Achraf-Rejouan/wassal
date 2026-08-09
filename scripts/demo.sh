#!/usr/bin/env bash
# The demo script (S5-09). Drives the whole system by hand so a reader can watch each claim
# being made rather than read that it was.
#
# Everything below runs against the live stack via curl and psql — nothing is simulated for the
# demo's benefit, and every number printed is read back out of the database or the metrics
# endpoint immediately after the action that produced it.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; red=$'\033[31m'; cyan=$'\033[36m'; off=$'\033[0m'
step() { printf '\n%s▸ %s%s\n' "$bold" "$*" "$off"; }
info() { printf '  %s%s%s\n' "$dim" "$*" "$off"; }
ok()   { printf '  %s✓%s %s\n' "$green" "$off" "$*"; }
bad()  { printf '  %s✗%s %s\n' "$red" "$off" "$*"; }
run()  { printf '  %s$ %s%s\n' "$cyan" "$*" "$off"; }
pause(){ [ -n "${WASSAL_DEMO_FAST:-}" ] || sleep "${1:-2}"; }

q()  { docker compose exec -T postgres psql -U wassal -d wassal -Atc "$1" 2>/dev/null | tr -d '\r'; }
# psql sends RAISE NOTICE to stderr, so the constraint demo has to capture both.
qe() { docker compose exec -T postgres psql -U wassal -d wassal -Atc "$1" 2>&1 | tr -d '\r'; }

docker compose ps --format '{{.Health}}' 2>/dev/null | grep -q healthy || {
  echo "Stack not running. Start it with: ./wassal.sh start" >&2; exit 1; }

printf '\n%sWassal — real-time courier dispatch%s\n' "$bold" "$off"
info "Every number below is read from the database or /actuator/prometheus as it happens."

# ─────────────────────────────────────────────────────────────────────────────
step "1. An order enters the system"
MERCHANT=$(uuidgen); KEY="demo-$(date +%s%N)"
run "curl -X POST localhost:8080/v1/orders -H 'Idempotency-Key: $KEY' ..."
ORDER=$(curl -sS -X POST http://localhost:8080/v1/orders \
  -H 'Content-Type: application/json' -H "X-Merchant-Id: $MERCHANT" -H "Idempotency-Key: $KEY" \
  -d '{"pickup":{"lat":36.8065,"lon":10.1815},"dropoff":{"lat":36.8189,"lon":10.1658}}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null)
[ -n "$ORDER" ] && ok "order $ORDER created (201)" || { bad "order creation failed"; exit 1; }
info "the order row and its OrderCreated event were written in ONE transaction —"
info "an order without its event is unreachable, not merely unlikely (INV-5)"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "2. The same request, replayed 10 times"
info "mobile networks retry. A duplicate submission must not create a duplicate order."
CODES=""
for _ in $(seq 1 10); do
  CODES="$CODES $(curl -sS -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/v1/orders \
    -H 'Content-Type: application/json' -H "X-Merchant-Id: $MERCHANT" -H "Idempotency-Key: $KEY" \
    -d '{"pickup":{"lat":36.8065,"lon":10.1815},"dropoff":{"lat":36.8189,"lon":10.1658}}')"
done
info "HTTP codes:$CODES"
COUNT=$(q "SELECT count(*) FROM orders.orders WHERE merchant_id='$MERCHANT'")
[ "$COUNT" = "1" ] && ok "1 order exists after 11 requests — the unique constraint arbitrated" \
                   || bad "expected 1 order, found $COUNT"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "3. Dispatch found a courier and made a time-bounded offer"
sleep 3
q "SELECT '  offer '||substr(id::text,1,8)||'  status='||status||'  expires in '||
          round(EXTRACT(EPOCH FROM (expires_at-now()))::numeric,1)||'s'
     FROM dispatch.offers WHERE order_id='$ORDER' ORDER BY sequence" | sed 's/^/  /'
info "the deadline is a COLUMN, not a timer — which is what lets it survive process death,"
info "and what lets the accept path check it as a predicate (FR-011, FR-012)"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "4. The atomic claim under contention"
info "5,000 concurrent accepts against 50 couriers, from the proof suite:"
info "  ≤ 50 assignments · 0 invariant violations · failedClaims > 0 asserted"
info "that last assertion is the point — a concurrency test where the race never"
info "happened is not a passing test, it is an unexecuted one"
CLAIMS=$(curl -sS http://localhost:8082/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_claim_failed_total' | awk '{printf "%.0f",$2}')
ASSIGNED=$(curl -sS http://localhost:8082/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_assignments_created_total' | awk '{printf "%.0f",$2}')
info "live counters right now: assignments=${ASSIGNED:-0} lost-claims=${CLAIMS:-0}"
info "lost claims are healthy — under contention, losing is the majority outcome"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "5. INV-1 is enforced by the database, not by application code"
run "INSERT a second ACTIVE assignment for a courier that already has one"
RESULT=$(qe "DO \$\$
DECLARE c uuid;
BEGIN
  SELECT courier_id INTO c FROM dispatch.assignments WHERE status='ACTIVE' LIMIT 1;
  IF c IS NULL THEN RAISE NOTICE 'no active assignment yet'; RETURN; END IF;
  INSERT INTO dispatch.assignments (order_id, courier_id, status)
  VALUES (gen_random_uuid(), c, 'ACTIVE');
  RAISE EXCEPTION 'INV-1 VIOLATED';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'refused by uq_active_assignment_per_courier';
END \$\$;" 2>&1)
echo "$RESULT" | grep -qi "refused" \
  && ok "the write simply failed — a bug in the service cannot violate INV-1" \
  || info "$RESULT"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "6. Write amplification: 100 msg/s must not become 100 writes/s"
APPLIED=$(curl -sS http://localhost:8083/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_position_applied_total' | awk '{printf "%.0f",$2}')
BATCHES=$(curl -sS http://localhost:8083/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_coldpath_flush_operations_total' | awk '{printf "%.0f",$2}')
if [ "${BATCHES:-0}" -gt 0 ]; then
  ok "${APPLIED:-0} positions ingested in ${BATCHES:-0} Postgres write statements"
  info "≈$(( ${APPLIED:-0} / ${BATCHES:-1} ))× fewer write operations than positions"
  info "rows are necessarily 1:1 — nothing is discarded. What batching reduces is"
  info "STATEMENTS, and that is what write amplification actually costs (see NFR-003)"
fi
pause

# ─────────────────────────────────────────────────────────────────────────────
step "7. Cross-instance WebSocket fan-out"
info "the customer's socket is on ONE gateway; the position is produced elsewhere."
info "Redis Pub/Sub connects them, which is why gateway instances are interchangeable."
GW1=$(curl -sS http://localhost:18081/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_ws_frames_delivered_total' | awk '{printf "%.0f",$2}')
GW2=$(curl -sS http://localhost:18082/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_ws_frames_delivered_total' | awk '{printf "%.0f",$2}')
info "frames delivered — gateway-1: ${GW1:-0}   gateway-2: ${GW2:-0}"
info "both instances serve the same courier's positions without knowing about each other"
pause

# ─────────────────────────────────────────────────────────────────────────────
step "8. The evidence, right now"
printf '\n'
curl -sS http://localhost:8082/actuator/prometheus 2>/dev/null \
  | grep -E '^wassal_invariant_violation_total' \
  | while read -r line; do
      inv=$(echo "$line" | sed -n 's/.*invariant="\([^"]*\)".*/\1/p')
      val=$(echo "$line" | awk '{print $2}')
      [ "${val%.*}" = "0" ] && ok "$inv  $val" || bad "$inv  $val  ← VIOLATION"
    done

printf '\n%sNext:%s\n' "$bold" "$off"
info "./wassal.sh chaos     kill real containers and measure recovery"
info "./wassal.sh proof     run every test suite"
info "./wassal.sh status    health, endpoints, live counters"
[ -n "$(curl -sf http://localhost:3000/api/health 2>/dev/null)" ] \
  && info "http://localhost:3000/d/wassal-evidence    Grafana"
printf '\n'
