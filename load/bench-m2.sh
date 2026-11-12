#!/usr/bin/env bash
# M2+M3 weekly benchmark: (1) refresh storm (load/k6/queue-storm.js) and (2) the weekly open run bot
# (load/k6/weekly-openrun.js, queue -> hold -> payment through the Fake PG), each on a fresh showtime that opens
# OPEN_DELAY_S after creation (entry is already open, admission starts then). After each run the showtime's
# queue-integrity (and, for the weekly run, payment-integrity) is saved, then the report prints.
#
#   usage: load/bench-m2.sh
#   env:   BASE_URL (default http://localhost:8080), OPEN_DELAY_S (default 20)
#          VUS (default 1000), STORM_DURATION_S (default 30)                     (storm)
#          DURATION_S (default 120), MEMBERS (default 2000), ADMISSIONS_PER_SECOND (default 100)   (weekly)
#
#        SETTLE_S (default 20): wait after the weekly run so the payment janitor (5 s tick) settles UNKNOWN payments
#
# Start the API with the queue on (default), the matching admission rate and the Fake PG, e.g.
#   OPENRUN_QUEUE_ADMISSIONS_PER_SECOND=100 OPENRUN_PAYMENT_PG=fake ./gradlew :apps:api:bootRun
# Uses a native k6 when on PATH, else the grafana/k6 docker image with the repo mounted at /work.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
OPEN_DELAY_S="${OPEN_DELAY_S:-20}"
VUS="${VUS:-1000}"
STORM_DURATION_S="${STORM_DURATION_S:-30}"
DURATION_S="${DURATION_S:-120}"
MEMBERS="${MEMBERS:-2000}"
ADMISSIONS_PER_SECOND="${ADMISSIONS_PER_SECOND:-100}"
SETTLE_S="${SETTLE_S:-20}"
mkdir -p load/results

echo "reminder: the API must run with the queue on and OPENRUN_QUEUE_ADMISSIONS_PER_SECOND=$ADMISSIONS_PER_SECOND"

K6_BASE_URL="$BASE_URL"
if ! command -v k6 > /dev/null && [[ "$(uname -s)" == "Darwin" ]]; then
  # Docker Desktop's VM does not share the host loopback unless host networking is enabled.
  K6_BASE_URL="${BASE_URL/localhost/host.docker.internal}"
  K6_BASE_URL="${K6_BASE_URL/127.0.0.1/host.docker.internal}"
fi

# run_k6 <script> [-e KEY=VALUE ...]; threshold failures are reported, not fatal (the report shows the numbers).
run_k6() {
  local script="$1"
  shift
  local args=(run --quiet -e "BASE_URL=$K6_BASE_URL" "$@")
  local status=0
  if command -v k6 > /dev/null; then
    k6 "${args[@]}" "$script" || status=$?
  else
    docker run --rm -i --network host -u "$(id -u):$(id -g)" -v "$PWD":/work -w /work \
      grafana/k6 "${args[@]}" "$script" || status=$?
  fi
  if [[ $status -ne 0 ]]; then echo "k6 exited with $status (99 = thresholds crossed)"; fi
}

# new_showtime: prints the id of a showtime opening OPEN_DELAY_S from now.
new_showtime() {
  python3 - "$BASE_URL" "$OPEN_DELAY_S" <<'PY'
import datetime, json, sys, urllib.request
base, delay = sys.argv[1], int(sys.argv[2])
now = datetime.datetime.now(datetime.timezone.utc)
body = json.dumps({
    "startsAt": (now + datetime.timedelta(days=7)).isoformat(),
    "openAt": (now + datetime.timedelta(seconds=delay)).isoformat(),
}).encode()
req = urllib.request.Request(f"{base}/api/admin/showtimes", body, {"Content-Type": "application/json"})
print(json.load(urllib.request.urlopen(req))["showtimeId"])
PY
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

STORM_ID="$(new_showtime)"
echo "=== storm showtime $STORM_ID: $VUS VUs x ${STORM_DURATION_S}s ($K6_BASE_URL)"
run_k6 load/k6/queue-storm.js -e "SHOWTIME_ID=$STORM_ID" -e "VUS=$VUS" -e "DURATION_S=$STORM_DURATION_S"
curl -fsS "$BASE_URL/api/admin/showtimes/$STORM_ID/queue-integrity" > "load/results/m2-integrity-storm-$STAMP.json"

WEEKLY_ID="$(new_showtime)"
echo "=== weekly showtime $WEEKLY_ID: $MEMBERS members over ${DURATION_S}s ($K6_BASE_URL)"
run_k6 load/k6/weekly-openrun.js -e "SHOWTIME_ID=$WEEKLY_ID" -e "DURATION_S=$DURATION_S" -e "MEMBERS=$MEMBERS" \
  -e "ADMISSIONS_PER_SECOND=$ADMISSIONS_PER_SECOND"
curl -fsS "$BASE_URL/api/admin/showtimes/$WEEKLY_ID/queue-integrity" > "load/results/m2-integrity-weekly-$STAMP.json"
echo "=== waiting ${SETTLE_S}s for the payment janitor"
sleep "$SETTLE_S"
curl -fsS "$BASE_URL/api/admin/showtimes/$WEEKLY_ID/payment-integrity" > "load/results/m3-payment-integrity-weekly-$STAMP.json"

python3 load/report/m2_report.py
