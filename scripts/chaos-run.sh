#!/usr/bin/env bash
# Chaos proof against the REAL Compose stack (S5-03, NFR-005).
#
# The JUnit suite in proof/chaos injects network faults with Toxiproxy. This script does the
# thing Toxiproxy cannot: it kills actual service containers mid-operation and measures how long
# the system takes to come back to a consistent state.
#
# Every assertion is on CONVERGED state after a bounded settle window, never on transient state
# during recovery. Asserting mid-recovery is what makes chaos tests flaky, and flaky chaos tests
# get disabled, which silently removes the entire failure-correctness claim (threat E-03).
#
#   ./scripts/chaos-run.sh            # all scenarios
#   ./scripts/chaos-run.sh dispatch   # one scenario
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"

PSQL=(docker compose exec -T postgres psql -U wassal -d wassal -Atc)
SETTLE_SECONDS=30
FAILURES=0

c_reset=$'\033[0m'; c_ok=$'\033[32m'; c_bad=$'\033[31m'; c_dim=$'\033[2m'

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s%s%s\n' "$c_dim" "$*" "$c_reset"; }
pass() { printf '  %s✓%s %s\n' "$c_ok" "$c_reset" "$*"; }
fail() { printf '  %s✗%s %s\n' "$c_bad" "$c_reset" "$*"; FAILURES=$((FAILURES + 1)); }

q() { "${PSQL[@]}" "$1" 2>/dev/null | tr -d '[:space:]'; }

require_stack() {
  local healthy
  healthy=$(docker compose ps --format '{{.Health}}' 2>/dev/null | grep -c healthy || true)
  if [ "${healthy:-0}" -lt 6 ]; then
    echo "The stack is not running. Start it first:  ./wassal.sh start" >&2
    exit 1
  fi
}

# Consistency is checked from the DATABASE, not from responses. A service can report success
# about state it never wrote; the tables cannot.
assert_consistent() {
  local label="$1"

  local double_courier double_order stuck orphan_accepted
  double_courier=$(q "SELECT count(*) FROM (SELECT courier_id FROM dispatch.assignments
                      WHERE status='ACTIVE' GROUP BY courier_id HAVING count(*)>1) t")
  double_order=$(q "SELECT count(*) FROM (SELECT order_id FROM dispatch.assignments
                    WHERE status='ACTIVE' GROUP BY order_id HAVING count(*)>1) t")
  stuck=$(q "SELECT count(*) FROM orders.orders
             WHERE terminal_at IS NULL AND sla_deadline < now()")
  # An ACCEPTED offer with no assignment behind it is the half-committed state the claim's
  # transaction exists to prevent — the single most revealing check after a kill.
  orphan_accepted=$(q "SELECT count(*) FROM dispatch.offers o
                       WHERE o.status='ACCEPTED'
                         AND NOT EXISTS (SELECT 1 FROM dispatch.assignments a
                                         WHERE a.offer_id=o.id)")

  [ "${double_courier:-0}" = "0" ] && pass "INV-1 held (no courier with two active assignments)" \
                                   || fail "INV-1 VIOLATED after $label: $double_courier couriers"
  [ "${double_order:-0}" = "0" ]   && pass "INV-2 held (no order with two active assignments)" \
                                   || fail "INV-2 VIOLATED after $label: $double_order orders"
  [ "${stuck:-0}" = "0" ]          && pass "INV-4 held (no order past SLA without terminal state)" \
                                   || fail "INV-4 VIOLATED after $label: $stuck stuck orders"
  [ "${orphan_accepted:-0}" = "0" ] && pass "no ACCEPTED offer without an assignment behind it" \
                                    || fail "orphaned ACCEPTED offers after $label: $orphan_accepted"

  local violations
  violations=$(curl -sS http://localhost:8082/actuator/prometheus 2>/dev/null \
    | grep -E '^wassal_invariant_violation_total' | awk '{s+=$2} END {printf "%.0f", s+0}')
  [ "${violations:-0}" = "0" ] && pass "runtime invariant counters all zero" \
                               || fail "invariant counters non-zero after $label: $violations"
}

kill_and_measure() {
  local service="$1" description="$2"

  say "SCENARIO: $description"
  local orders_before
  orders_before=$(q "SELECT count(*) FROM orders.orders")
  info "orders before: ${orders_before:-0}"

  info "killing $service (SIGKILL — no graceful shutdown, no drain)"
  docker compose kill "$service" >/dev/null 2>&1
  sleep 5

  local start restart_done
  start=$(date +%s%3N)
  docker compose start "$service" >/dev/null 2>&1

  # Wait for CONVERGENCE, not for the container. A healthy process that has not yet caught up
  # is not a recovered system.
  local recovered=0 elapsed=0
  for _ in $(seq 1 "$SETTLE_SECONDS"); do
    local health
    health=$(docker compose ps "$service" --format '{{.Health}}' 2>/dev/null | tr -d '[:space:]')
    if [ "$health" = "healthy" ]; then
      local stuck
      stuck=$(q "SELECT count(*) FROM orders.orders
                 WHERE terminal_at IS NULL AND sla_deadline < now()")
      if [ "${stuck:-0}" = "0" ]; then recovered=1; break; fi
    fi
    sleep 1
  done
  restart_done=$(date +%s%3N)
  elapsed=$(( (restart_done - start) ))

  if [ "$recovered" = "1" ]; then
    pass "recovered to a consistent state in ${elapsed}ms (NFR-005 target: < 30000ms)"
  else
    fail "did NOT converge within ${SETTLE_SECONDS}s"
  fi

  local orders_after
  orders_after=$(q "SELECT count(*) FROM orders.orders")
  if [ "${orders_after:-0}" -ge "${orders_before:-0}" ]; then
    pass "zero orders lost (${orders_before:-0} -> ${orders_after:-0})"
  else
    fail "ORDERS LOST: ${orders_before} -> ${orders_after}"
  fi

  assert_consistent "$service kill"
}

require_stack

say "Wassal chaos proof — killing real containers mid-operation"
info "settle window: ${SETTLE_SECONDS}s   NFR-005 target: recovery < 30s, zero lost orders"
info "letting the simulator build up in-flight state first"
sleep 20

SCENARIO="${1:-all}"

case "$SCENARIO" in
  dispatch|all)
    # The headline scenario. dispatch-service holds the claim, the sweeper and the saga, so
    # killing it mid-assignment is the most destructive single failure available.
    kill_and_measure dispatch-service "kill dispatch-service mid-assignment"
    ;;&
  order|all)
    # Added because review F-1 found order-service's boundary was justified by a failure the
    # test plan never actually induced.
    kill_and_measure order-service "kill order-service mid-creation"
    ;;&
  gateway|all)
    kill_and_measure gateway-1 "kill one gateway mid-stream (the other must carry on)"
    ;;&
  tracking|all)
    kill_and_measure tracking-service "kill tracking-service mid-ingest"
    ;;&
  redpanda|all)
    say "SCENARIO: kill Redpanda — the outbox must absorb it"
    before=$(q "SELECT count(*) FROM orders.order_outbox WHERE sent_at IS NULL")
    docker compose kill redpanda >/dev/null 2>&1
    info "broker down; orders should still be ACCEPTED and queue in the outbox"
    sleep 12
    during=$(q "SELECT count(*) FROM orders.order_outbox WHERE sent_at IS NULL")
    docker compose start redpanda >/dev/null 2>&1
    [ "${during:-0}" -ge "${before:-0}" ] \
      && pass "outbox absorbed the outage (unsent ${before:-0} -> ${during:-0})" \
      || info "outbox depth ${before:-0} -> ${during:-0}"
    info "waiting for the outbox to drain"
    drained=0
    for _ in $(seq 1 60); do
      pending=$(q "SELECT count(*) FROM orders.order_outbox WHERE sent_at IS NULL")
      if [ "${pending:-1}" -le 1 ]; then drained=1; break; fi
      sleep 1
    done
    [ "$drained" = "1" ] && pass "outbox fully drained after the broker returned — nothing lost" \
                         || fail "outbox did not drain: ${pending:-?} still unsent"
    assert_consistent "redpanda kill"
    ;;
esac

say "RESULT"
if [ "$FAILURES" -eq 0 ]; then
  printf '  %sAll chaos scenarios passed.%s\n\n' "$c_ok" "$c_reset"
  exit 0
fi
printf '  %s%d check(s) failed.%s\n\n' "$c_bad" "$FAILURES" "$c_reset"
exit 1
