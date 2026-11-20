#!/usr/bin/env bash
# Weekly open run inside one runner (ADR-0008 §2, docs/requirements.md §2, M4-4): create today's showtime opening at
# 20:00 KST (startsAt +7 days), wait until queue entry opens (openAt - entry lead), run the weekly bot
# (load/k6/weekly-openrun.js), wait SETTLE_S for the hold TTL and the payment janitor, then save the integrity JSONs
# and a run meta file for load/report/weekly_report.py. Writes into RESULTS_DIR, all with one <stamp>:
#   weekly-run-<stamp>.json                 meta: showtime, open_at (actual), k6 start/end, app_commit, scenario_hash
#   weekly-seats-<stamp>.json               POST /api/admin/showtimes response (seat ids for the k6 setup)
#   m2-weekly-<k6 stamp>.json               k6 handleSummary
#   weekly-integrity-<stamp>.json           GET /api/admin/showtimes/{id}/integrity
#   weekly-queue-integrity-<stamp>.json     GET /api/admin/showtimes/{id}/queue-integrity
#   weekly-payment-integrity-<stamp>.json   GET /api/admin/showtimes/{id}/payment-integrity
#   weekly-illegal-transition-<stamp>.json  GET /actuator/metrics/openrun.api.errors?tag=code:ILLEGAL_TRANSITION
#
#   usage: load/weekly-openrun.sh
#   env:   BASE_URL (default http://localhost:8080)
#          OPEN_AT (ISO-8601; default today 20:00 Asia/Seoul). If it is past or less than MIN_LEAD_S (default 60)
#            ahead, openAt = now + MIN_LEAD_S and the meta records open_at_adjusted: true
#          OPEN_DELAY_S (overrides OPEN_AT with now + delay; local and workflow_dispatch test runs)
#          ENTRY_LEAD_S (default 600; must match the API's openrun.queue.entry-lead, default PT10M)
#          DURATION_S (default 1200), MEMBERS (default: yaml virtual_users), ADMISSIONS_PER_SECOND (default 100)
#          SETTLE_S (default 900: hold TTL 12 min + UNKNOWN release margin)
#          SCENARIO (repo-relative, default load/scenarios/weekly-default.yaml), RESULTS_DIR (default load/results)
#
# Start the API with the queue on, the matching admission rate and the Fake PG with webhooks, e.g.
#   OPENRUN_QUEUE_ADMISSIONS_PER_SECOND=100 OPENRUN_PAYMENT_PG=fake OPENRUN_FAKE_PG_WEBHOOKS=true ./gradlew :apps:api:bootRun
# Uses a native k6 when on PATH, else the grafana/k6 docker image with the repo mounted at /work.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
OPEN_AT="${OPEN_AT:-}"
OPEN_DELAY_S="${OPEN_DELAY_S:-}"
MIN_LEAD_S="${MIN_LEAD_S:-60}"
ENTRY_LEAD_S="${ENTRY_LEAD_S:-600}"
DURATION_S="${DURATION_S:-1200}"
MEMBERS="${MEMBERS:-}"
ADMISSIONS_PER_SECOND="${ADMISSIONS_PER_SECOND:-100}"
SETTLE_S="${SETTLE_S:-900}"
SCENARIO="${SCENARIO:-load/scenarios/weekly-default.yaml}"
RESULTS_DIR="${RESULTS_DIR:-load/results}"
mkdir -p "$RESULTS_DIR"
RESULTS_ABS="$(cd "$RESULTS_DIR" && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

# ADR-0009 §3: with OPENRUN_ADMIN_TOKEN set (demo server), every /api/admin call carries X-Admin-Token and k6 gets the
# value as -e OPENRUN_ADMIN_TOKEN (its setup/teardown call /api/admin too). Unset = no header, as before.
ADMIN_CURL=()
ADMIN_K6=()
if [[ -n "${OPENRUN_ADMIN_TOKEN:-}" ]]; then
  ADMIN_CURL=(-H "X-Admin-Token: $OPENRUN_ADMIN_TOKEN")
  ADMIN_K6=(-e "OPENRUN_ADMIN_TOKEN=$OPENRUN_ADMIN_TOKEN")
fi

echo "reminder: the API must run with the queue on and OPENRUN_QUEUE_ADMISSIONS_PER_SECOND=$ADMISSIONS_PER_SECOND"

K6_BASE_URL="$BASE_URL"
if ! command -v k6 > /dev/null && [[ "$(uname -s)" == "Darwin" ]]; then
  # Docker Desktop's VM does not share the host loopback unless host networking is enabled.
  K6_BASE_URL="${BASE_URL/localhost/host.docker.internal}"
  K6_BASE_URL="${K6_BASE_URL/127.0.0.1/host.docker.internal}"
fi

# now_iso: current UTC time, ISO-8601 with milliseconds.
now_iso() {
  python3 -c 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds"))'
}

# 1. openAt: OPEN_DELAY_S > OPEN_AT > today 20:00 KST; never closer than MIN_LEAD_S. Prints "<openAt> <adjusted>".
read -r OPEN_AT_ISO OPEN_AT_ADJUSTED < <(python3 - "$OPEN_AT" "$OPEN_DELAY_S" "$MIN_LEAD_S" <<'PY'
import datetime, sys
open_at, delay, min_lead = sys.argv[1], sys.argv[2], int(sys.argv[3])
utc = datetime.timezone.utc
now = datetime.datetime.now(utc)
if delay:
    at = now + datetime.timedelta(seconds=int(delay))
elif open_at:
    at = datetime.datetime.fromisoformat(open_at.replace("Z", "+00:00"))
    if at.tzinfo is None:
        sys.exit(f"OPEN_AT needs an offset: {open_at}")
else:
    kst = datetime.timezone(datetime.timedelta(hours=9))  # Asia/Seoul has no DST
    at = now.astimezone(kst).replace(hour=20, minute=0, second=0, microsecond=0)
adjusted = at < now + datetime.timedelta(seconds=min_lead)
if adjusted:
    at = now + datetime.timedelta(seconds=min_lead)
print(at.astimezone(utc).isoformat(timespec="milliseconds"), "true" if adjusted else "false")
PY
)
if [[ -z "${OPEN_AT_ISO:-}" ]]; then echo "could not compute openAt" >&2; exit 1; fi

# 2. Create the showtime (startsAt = openAt + 7 days). The response keeps the seat ids for the k6 setup.
SEATS_FILE="$RESULTS_ABS/weekly-seats-$STAMP.json"
python3 - "$BASE_URL" "$OPEN_AT_ISO" > "$SEATS_FILE" <<'PY'
import datetime, json, os, sys, urllib.request
base, open_at = sys.argv[1], datetime.datetime.fromisoformat(sys.argv[2])
body = json.dumps({
    "startsAt": (open_at + datetime.timedelta(days=7)).isoformat(),
    "openAt": open_at.isoformat(),
}).encode()
headers = {"Content-Type": "application/json"}
if os.environ.get("OPENRUN_ADMIN_TOKEN"):
    headers["X-Admin-Token"] = os.environ["OPENRUN_ADMIN_TOKEN"]
req = urllib.request.Request(f"{base}/api/admin/showtimes", body, headers)
print(urllib.request.urlopen(req).read().decode())
PY
SHOWTIME_ID="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["showtimeId"])' "$SEATS_FILE")"
ENTRY_OPENS_AT="$(python3 -c '
import datetime, sys
at = datetime.datetime.fromisoformat(sys.argv[1]) - datetime.timedelta(seconds=int(sys.argv[2]))
print(at.isoformat(timespec="milliseconds"))' "$OPEN_AT_ISO" "$ENTRY_LEAD_S")"
echo "=== showtime $SHOWTIME_ID: openAt $OPEN_AT_ISO (adjusted: $OPEN_AT_ADJUSTED), entry opens $ENTRY_OPENS_AT"

# 3. Wait until entry opens (already open when OPEN_DELAY_S < ENTRY_LEAD_S).
while :; do
  remaining="$(python3 -c '
import datetime, math, sys
at = datetime.datetime.fromisoformat(sys.argv[1])
print(max(0, math.ceil((at - datetime.datetime.now(datetime.timezone.utc)).total_seconds())))' "$ENTRY_OPENS_AT")"
  if [[ "$remaining" -le 0 ]]; then break; fi
  echo "entry opens in ${remaining}s"
  sleep "$((remaining < 60 ? remaining : 60))"
done

# 4. k6. Threshold failures (exit 99) are reported, not fatal: the integrity checks and the report still run.
k6_args=(run --quiet -e "BASE_URL=$K6_BASE_URL" -e "SHOWTIME_ID=$SHOWTIME_ID" -e "DURATION_S=$DURATION_S"
  -e "ADMISSIONS_PER_SECOND=$ADMISSIONS_PER_SECOND" -e "SCENARIO=$SCENARIO")
if [[ -n "$MEMBERS" ]]; then k6_args+=(-e "MEMBERS=$MEMBERS"); fi
k6_args+=(${ADMIN_K6[@]+"${ADMIN_K6[@]}"})
# Admission tick times (openrun.queue.tick) for this run only; saved after the settle as weekly-ticks.
curl -sf ${ADMIN_CURL[@]+"${ADMIN_CURL[@]}"} "$BASE_URL/api/admin/diagnostics/queue-ticks?reset=true" > /dev/null || echo "queue-ticks reset failed"
K6_STARTED_AT="$(now_iso)"
echo "=== k6 weekly-openrun.js from $K6_STARTED_AT: ${MEMBERS:-yaml} members over ${DURATION_S}s ($K6_BASE_URL)"
status=0
if command -v k6 > /dev/null; then
  k6 "${k6_args[@]}" -e "RESULTS_DIR=$RESULTS_ABS" -e "SEATS_FILE=$SEATS_FILE" load/k6/weekly-openrun.js || status=$?
else
  docker run --rm -i --network host -u "$(id -u):$(id -g)" -v "$PWD":/work -v "$RESULTS_ABS":/results -w /work \
    grafana/k6 "${k6_args[@]}" -e "RESULTS_DIR=/results" -e "SEATS_FILE=/results/$(basename "$SEATS_FILE")" \
    load/k6/weekly-openrun.js || status=$?
fi
K6_FINISHED_AT="$(now_iso)"
if [[ $status -ne 0 ]]; then echo "k6 exited with $status (99 = thresholds crossed)"; fi
# The k6 summary written by this run: the newest m2-weekly-*.json.
K6_SUMMARY="$(ls -t "$RESULTS_ABS"/m2-weekly-*.json 2> /dev/null | head -1 || true)"

# 5. Settle, then verify. A failed fetch leaves the file out; the report prints n/a for it. All four are /api/admin.
echo "=== waiting ${SETTLE_S}s (hold TTL + payment janitor) before the integrity checks"
sleep "$SETTLE_S"
fetch() {
  curl -fsS ${ADMIN_CURL[@]+"${ADMIN_CURL[@]}"} "$BASE_URL$1" > "$2.tmp" && mv "$2.tmp" "$2" || { rm -f "$2.tmp"; echo "fetch failed: $1"; }
}
fetch "/api/admin/showtimes/$SHOWTIME_ID/integrity" "$RESULTS_ABS/weekly-integrity-$STAMP.json"
fetch "/api/admin/showtimes/$SHOWTIME_ID/queue-integrity" "$RESULTS_ABS/weekly-queue-integrity-$STAMP.json"
fetch "/api/admin/showtimes/$SHOWTIME_ID/payment-integrity" "$RESULTS_ABS/weekly-payment-integrity-$STAMP.json"
fetch "/api/admin/diagnostics/queue-ticks" "$RESULTS_ABS/weekly-ticks-$STAMP.json"
# Process-wide counter, registered on first increment: 404 means it never fired, i.e. 0.
ILLEGAL="$RESULTS_ABS/weekly-illegal-transition-$STAMP.json"
code="$(curl -sS -o "$ILLEGAL.tmp" -w '%{http_code}' "$BASE_URL/actuator/metrics/openrun.api.errors?tag=code:ILLEGAL_TRANSITION" || true)"
if [[ "$code" == "200" ]]; then
  mv "$ILLEGAL.tmp" "$ILLEGAL"
elif [[ "$code" == "404" ]]; then
  rm -f "$ILLEGAL.tmp"
  echo '{"name":"openrun.api.errors","measurements":[{"statistic":"COUNT","value":0.0}],"absent":true}' > "$ILLEGAL"
else
  rm -f "$ILLEGAL.tmp"
  echo "fetch failed: illegal transition counter (HTTP $code)"
fi

# 6. Run meta for the report.
APP_COMMIT="$(git rev-parse --short HEAD 2> /dev/null || echo unknown)"
SCENARIO_HASH="$(git hash-object "$SCENARIO" 2> /dev/null || echo unknown)"
python3 - "$RESULTS_ABS/weekly-run-$STAMP.json" <<PY
import json, os, sys
summary_path = "$K6_SUMMARY"
summary = json.load(open(summary_path)) if summary_path and os.path.exists(summary_path) else {}
members = "$MEMBERS"
meta = {
    "stamp": "$STAMP",
    "showtime_id": int("$SHOWTIME_ID"),
    "open_at": "$OPEN_AT_ISO",
    "open_at_adjusted": "$OPEN_AT_ADJUSTED" == "true",
    "entry_opens_at": "$ENTRY_OPENS_AT",
    "k6_started_at": "$K6_STARTED_AT",
    "k6_finished_at": "$K6_FINISHED_AT",
    "k6_exit_code": $status,
    "k6_summary": os.path.basename(summary_path) if summary_path else None,
    "settle_s": int("$SETTLE_S"),
    "duration_s": int("$DURATION_S"),
    "members": int(members) if members else summary.get("members"),
    "members_from": "env" if members else "scenario virtual_users",
    "admissions_per_second": int("$ADMISSIONS_PER_SECOND"),
    "scenario": "$SCENARIO",
    "app_commit": "$APP_COMMIT",
    "scenario_hash": "$SCENARIO_HASH",
}
with open(sys.argv[1], "w") as f:
    json.dump(meta, f, indent=2)
PY

# 7. Short summary.
python3 - "$RESULTS_ABS" "$STAMP" <<'PY'
import json, os, sys
d, stamp = sys.argv[1], sys.argv[2]
def load(name):
    p = os.path.join(d, f"{name}-{stamp}.json")
    return json.load(open(p)) if os.path.exists(p) else {}
meta, i, q, p = load("weekly-run"), load("weekly-integrity"), load("weekly-queue-integrity"), load("weekly-payment-integrity")
print(f"=== weekly open run {stamp}: showtime {meta['showtime_id']}, open_at {meta['open_at']} "
      f"(adjusted {meta['open_at_adjusted']}), k6 {meta['k6_started_at']} -> {meta['k6_finished_at']}")
print(f"oversell_count {i.get('oversell_count', 'n/a')} · confirm_expire_race_remaining "
      f"{p.get('confirm_expire_race_remaining', 'n/a')} · compensation_failed_count {p.get('compensation_failed_count', 'n/a')} · "
      f"state_event_mismatch_count {p.get('state_event_mismatch_count', 'n/a')}")
print(f"queue entered/admitted/abandoned/lost {q.get('entered', 'n/a')}/{q.get('admitted', 'n/a')}/"
      f"{q.get('abandoned', 'n/a')}/{q.get('lost', 'n/a')} · admission_rate_actual {q.get('admission_rate_actual', 'n/a')}")
print(f"results: {d}/*-{stamp}.json")
PY
