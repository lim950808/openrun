#!/usr/bin/env bash
# M1 lock-strategy benchmark: for each run x strategy, runs the arrival-rate bench (load/k6/bench-m1-hold.js) and
# then the single-seat burst (load/k6/bench-m1-burst.js), then prints the report tables. Around each arrival run it
# resets and snapshots /api/admin/diagnostics/hold-phases into load/results/m1-phases-<STRATEGY>-run<N>.json.
#
#   usage: load/bench-m1.sh [runs=3] [strategies=all]     strategies: all | comma list, e.g. NAIVE,REDIS_LUA
#   env:   BASE_URL (default http://localhost:8080), PEAK_RPS, DURATION_S, SCENARIO   (arrival bench)
#          BURST_VUS (default 100), BURST_DURATION_S (default 20)                     (burst)
#
# The API must run with OPENRUN_HOLD_ALLOW_OVERRIDE=true so the X-Hold-Strategy header is honored, and with
# OPENRUN_QUEUE_REQUIRE_ACCESS_TOKEN=false so the seat APIs take X-Member-Id without the queue (ADR-0006 §3).
# Uses a native k6 when on PATH, else the grafana/k6 docker image with the repo mounted at /work.
set -euo pipefail

cd "$(dirname "$0")/.."

RUNS="${1:-3}"
STRATEGIES="${2:-all}"
if [[ "$STRATEGIES" == "all" ]]; then
  STRATEGIES="NAIVE,FOR_UPDATE,SKIP_LOCKED,CONDITIONAL_UPDATE,REDIS_LUA"
fi
BASE_URL="${BASE_URL:-http://localhost:8080}"
mkdir -p load/results

echo "reminder: start the API with the bench settings from the scenario yaml 'app:' block:"
echo "  OPENRUN_HOLD_TTL=PT10S OPENRUN_HOLD_JANITOR_DELAY_MS=1000 OPENRUN_HOLD_ALLOW_OVERRIDE=true OPENRUN_QUEUE_REQUIRE_ACCESS_TOKEN=false"

# run_k6 <script> <strategy> [-e KEY=VALUE ...]
run_k6() {
  local script="$1" strategy="$2"
  shift 2
  local args=(run --quiet -e "STRATEGY=$strategy" -e "BASE_URL=$K6_BASE_URL" "$@")
  if command -v k6 > /dev/null; then
    k6 "${args[@]}" "$script"
  else
    docker run --rm -i --network host -u "$(id -u):$(id -g)" -v "$PWD":/work -w /work \
      grafana/k6 "${args[@]}" "$script"
  fi
}

ARRIVAL_ENV=()
for v in PEAK_RPS DURATION_S SCENARIO; do
  if [[ -n "${!v:-}" ]]; then ARRIVAL_ENV+=(-e "$v=${!v}"); fi
done
BURST_ENV=(-e "VUS=${BURST_VUS:-100}" -e "DURATION_S=${BURST_DURATION_S:-20}")

K6_BASE_URL="$BASE_URL"
if ! command -v k6 > /dev/null && [[ "$(uname -s)" == "Darwin" ]]; then
  # Docker Desktop's VM does not share the host loopback unless host networking is enabled.
  K6_BASE_URL="${BASE_URL/localhost/host.docker.internal}"
  K6_BASE_URL="${K6_BASE_URL/127.0.0.1/host.docker.internal}"
fi

IFS=',' read -r -a STRATEGY_LIST <<< "$STRATEGIES"
for run in $(seq 1 "$RUNS"); do
  for strategy in "${STRATEGY_LIST[@]}"; do
    echo "=== run $run/$RUNS strategy $strategy arrival ($K6_BASE_URL)"
    # Phase timers are cumulative per strategy: reset before the arrival run, snapshot right after it (before burst).
    curl -sf "$BASE_URL/api/admin/diagnostics/hold-phases?strategy=$strategy&reset=true" > /dev/null
    run_k6 load/k6/bench-m1-hold.js "$strategy" ${ARRIVAL_ENV[@]+"${ARRIVAL_ENV[@]}"}
    curl -sf "$BASE_URL/api/admin/diagnostics/hold-phases?strategy=$strategy" > "load/results/m1-phases-$strategy-run$run.json"
    echo "=== run $run/$RUNS strategy $strategy burst ($K6_BASE_URL)"
    run_k6 load/k6/bench-m1-burst.js "$strategy" "${BURST_ENV[@]}"
  done
done

python3 load/report/m1_report.py
