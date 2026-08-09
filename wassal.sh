#!/usr/bin/env bash
# Wassal — one script to start, stop and drive the whole system.
#
# NFR-007 requires a cold clone to reach a running stack in one command, under two minutes.
# `docker compose up` alone technically satisfies that; this script exists because the useful
# operations around it (which profile, is it actually healthy, run the proofs, tear down
# cleanly) were otherwise a set of commands a reader would have to already know.
#
#   ./wassal.sh start            core + simulator + observability
#   ./wassal.sh start --minimal  no simulator, no observability
#   ./wassal.sh start --quiet    no simulator (stack idle, drive it by hand)
#   ./wassal.sh stop             stop containers, KEEP data
#   ./wassal.sh down             stop and DELETE all data and volumes
#   ./wassal.sh status           health, ports, invariant counters
#   ./wassal.sh logs [service]   follow logs
#   ./wassal.sh demo             scripted walkthrough of the whole system
#   ./wassal.sh proof            unit + integration + concurrency + durability
#   ./wassal.sh chaos            kill real containers, measure recovery
#   ./wassal.sh psql             a psql shell
#   ./wassal.sh rebuild          rebuild images after a code change
set -uo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE=(-f docker-compose.yml)
OBS=(-f docker-compose.observability.yml)

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; red=$'\033[31m'; yellow=$'\033[33m'; off=$'\033[0m'

say()  { printf '\n%s%s%s\n' "$bold" "$*" "$off"; }
info() { printf '  %s%s%s\n' "$dim" "$*" "$off"; }
ok()   { printf '  %s✓%s %s\n' "$green" "$off" "$*"; }
warn() { printf '  %s!%s %s\n' "$yellow" "$off" "$*"; }
bad()  { printf '  %s✗%s %s\n' "$red" "$off" "$*"; }

preflight() {
  command -v docker >/dev/null 2>&1 || { bad "docker not found"; exit 1; }
  docker compose version >/dev/null 2>&1 || { bad "docker compose v2 not found"; exit 1; }
  docker info >/dev/null 2>&1 || { bad "docker daemon not reachable"; exit 1; }
}

# Waits for CONVERGENCE, not for containers to exist. "Started" and "ready to serve" are
# different things, and NFR-007's two-minute budget is about the second one.
wait_healthy() {
  local expected="$1" waited=0 limit=180
  while [ "$waited" -lt "$limit" ]; do
    local healthy total
    healthy=$(docker compose ps --format '{{.Health}}' 2>/dev/null | grep -c healthy || true)
    total=$(docker compose ps --format '{{.Service}}' 2>/dev/null | wc -l | tr -d ' ')
    printf '\r  %swaiting: %s/%s healthy (%ss)%s   ' "$dim" "${healthy:-0}" "$expected" "$waited" "$off"
    if [ "${healthy:-0}" -ge "$expected" ]; then printf '\r%*s\r' 60 ''; return 0; fi
    sleep 2; waited=$((waited + 2))
  done
  printf '\r%*s\r' 60 ''
  return 1
}

cmd_start() {
  local profile="full"
  for arg in "$@"; do
    case "$arg" in
      --minimal) profile="minimal" ;;
      --quiet)   profile="quiet" ;;
      *) bad "unknown option: $arg"; exit 1 ;;
    esac
  done

  preflight
  local start_ts; start_ts=$(date +%s)

  case "$profile" in
    minimal)
      say "Starting Wassal — minimal (no simulator, no observability)"
      info "order, dispatch, tracking, 2x gateway, proxy, postgres, redis, redpanda"
      docker compose "${BASE[@]}" up -d --scale simulator=0 >/dev/null 2>&1
      wait_healthy 9 || { bad "stack did not become healthy"; docker compose ps; exit 1; }
      ;;
    quiet)
      say "Starting Wassal — quiet (stack up, simulator off)"
      docker compose "${BASE[@]}" "${OBS[@]}" up -d --scale simulator=0 >/dev/null 2>&1
      wait_healthy 11 || { bad "stack did not become healthy"; docker compose ps; exit 1; }
      ;;
    *)
      say "Starting Wassal — full stack with 300 simulated couriers"
      docker compose "${BASE[@]}" "${OBS[@]}" up -d >/dev/null 2>&1
      wait_healthy 11 || { bad "stack did not become healthy"; docker compose ps; exit 1; }
      ;;
  esac

  local elapsed=$(( $(date +%s) - start_ts ))
  ok "healthy in ${elapsed}s $(if [ "$elapsed" -lt 120 ]; then echo "(NFR-007 target: < 120s)"; else echo "${red}(over the 120s NFR-007 target)${off}"; fi)"
  cmd_status
}

cmd_stop() {
  say "Stopping Wassal — data preserved"
  docker compose "${BASE[@]}" "${OBS[@]}" stop >/dev/null 2>&1
  ok "stopped. './wassal.sh start' resumes with the same data"
}

cmd_down() {
  say "Tearing down Wassal — deleting all data"
  # There is nothing to preserve: every run regenerates from a seed, which is why the project
  # has no backups at all (a genuine property, not a shortcut).
  docker compose "${BASE[@]}" "${OBS[@]}" down -v --remove-orphans >/dev/null 2>&1
  ok "containers and volumes removed"
}

cmd_status() {
  say "Services"
  docker compose ps --format 'table {{.Service}}\t{{.Status}}' 2>/dev/null | sed 's/^/  /'

  local healthy
  healthy=$(docker compose ps --format '{{.Health}}' 2>/dev/null | grep -c healthy || true)
  [ "${healthy:-0}" -gt 0 ] || { warn "nothing running — './wassal.sh start'"; return; }

  say "Endpoints"
  info "API (via proxy, 2x gateway)   http://localhost:8080/v1/orders"
  info "gateway-1 direct              http://localhost:18081"
  info "gateway-2 direct              http://localhost:18082"
  info "dispatch                      http://localhost:8082"
  info "tracking                      http://localhost:8083"
  curl -sf http://localhost:3000/api/health >/dev/null 2>&1 \
    && info "Grafana (anonymous)           http://localhost:3000/d/wassal-evidence"
  curl -sf http://localhost:9090/-/healthy >/dev/null 2>&1 \
    && info "Prometheus                    http://localhost:9090"

  say "Invariants (must all be zero)"
  local metrics
  metrics=$(curl -sS http://localhost:8082/actuator/prometheus 2>/dev/null || true)
  if [ -z "$metrics" ]; then warn "dispatch metrics unavailable"; return; fi
  echo "$metrics" | grep -E '^wassal_invariant_violation_total' | while read -r line; do
    local inv val
    inv=$(echo "$line" | sed -n 's/.*invariant="\([^"]*\)".*/\1/p')
    val=$(echo "$line" | awk '{print $2}')
    if [ "${val%.*}" = "0" ]; then ok "$inv  $val"; else bad "$inv  $val  ← VIOLATION"; fi
  done

  say "Activity"
  # Plain SQL, no printf templating: %L in a psql format string collides with printf's own
  # conversions and the shell wins that argument.
  docker compose exec -T postgres psql -U wassal -d wassal -Atc \
    "SELECT (SELECT count(*) FROM orders.orders)
         || ' orders, ' || (SELECT count(*) FROM dispatch.offers)
         || ' offers, ' || (SELECT count(*) FROM dispatch.assignments)
         || ' assignments, ' || (SELECT count(*) FROM dispatch.couriers WHERE status='AVAILABLE')
         || ' available couriers, ' || (SELECT count(*) FROM tracking.location_history)
         || ' positions'" 2>/dev/null | sed 's/^/  /'
}

cmd_logs()   { docker compose logs -f --tail=100 "${1:-}"; }
cmd_psql()   { docker compose exec postgres psql -U wassal -d wassal; }

cmd_rebuild() {
  say "Rebuilding images"
  ./gradlew build -x test -q || { bad "gradle build failed"; exit 1; }
  docker compose "${BASE[@]}" build >/dev/null 2>&1 || { bad "image build failed"; exit 1; }
  ok "images rebuilt — './wassal.sh start' to run them"
}

cmd_proof() {
  say "Running the proof suites"
  info "unit + integration + architecture + concurrency + durability"
  info "(chaos is separate and slower: './wassal.sh chaos')"
  ./gradlew build || exit 1
  ok "all proofs green"
}

cmd_chaos() { exec ./scripts/chaos-run.sh "${1:-all}"; }
cmd_demo()  { exec ./scripts/demo.sh; }

case "${1:-}" in
  start)   shift; cmd_start "$@" ;;
  stop)    cmd_stop ;;
  down)    cmd_down ;;
  restart) cmd_stop; cmd_start ;;
  status)  cmd_status ;;
  logs)    shift; cmd_logs "${1:-}" ;;
  psql)    cmd_psql ;;
  rebuild) cmd_rebuild ;;
  proof)   cmd_proof ;;
  chaos)   shift; cmd_chaos "${1:-all}" ;;
  demo)    cmd_demo ;;
  ""|help|-h|--help)
     sed -n "2,26p" "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
  *) bad "unknown command: $1"; echo "  try: ./wassal.sh help"; exit 1 ;;
esac
