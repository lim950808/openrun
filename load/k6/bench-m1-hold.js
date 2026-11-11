// M1 seat-hold benchmark (docs/requirements.md M1-3, metrics in docs/metrics.md "M1 좌석 선점").
// One run = one fresh showtime, one lock strategy, ramping-arrival-rate stages from the scenario yaml.
//
//   STRATEGY    required: NAIVE | FOR_UPDATE | SKIP_LOCKED | CONDITIONAL_UPDATE | REDIS_LUA
//               (the API must run with OPENRUN_HOLD_ALLOW_OVERRIDE=true)
//   BASE_URL    default http://localhost:8080
//   SCENARIO    repo-relative yaml path, default load/scenarios/bench-m1-hold.yaml
//   PEAK_RPS    local override: scales every stage target so the highest equals this
//   DURATION_S  local override: scales every stage duration so the total equals this
//   RESULTS_DIR default load/results (relative to the k6 working directory = repo root)
import http from 'k6/http';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import yaml from './vendor/js-yaml.mjs';

const STRATEGY = __ENV.STRATEGY;
if (!STRATEGY) throw new Error('STRATEGY is required');
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'load/scenarios/bench-m1-hold.yaml';
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load/results';

// open() resolves relative paths against this script's directory (load/k6), so climb to the repo root.
const scenario = yaml.load(open(SCENARIO.startsWith('/') ? SCENARIO : `../../${SCENARIO}`));

const yamlStages = scenario.arrival.stages;
const yamlPeak = Math.max(...yamlStages.map((s) => s.target_rps));
const yamlTotalS = yamlStages.reduce((sum, s) => sum + s.duration_seconds, 0);
const rpsScale = __ENV.PEAK_RPS ? Number(__ENV.PEAK_RPS) / yamlPeak : 1;
const timeScale = __ENV.DURATION_S ? Number(__ENV.DURATION_S) / yamlTotalS : 1;
const stages = yamlStages.map((s) => ({
  duration: `${Math.max(1, Math.round(s.duration_seconds * timeScale * 1000))}ms`,
  target: Math.round(s.target_rps * rpsScale),
}));
const PEAK_RPS = Math.round(yamlPeak * rpsScale);
const DURATION_S = yamlTotalS * timeScale;
const HOLD_TTL = scenario.app && scenario.app.hold_ttl ? scenario.app.hold_ttl : null;

// Scenario yaml has no seats-per-hold distribution yet; requirements §1 allows 1~2 seats.
const SEAT_CHOICE = scenario.seat_choice || { hot_ratio: 0, hot_pool: 0 };
const HOT_RATIO = SEAT_CHOICE.hot_ratio;
const HOT_POOL = SEAT_CHOICE.hot_pool;
const TWO_SEAT_SHARE = scenario.seats_per_hold && scenario.seats_per_hold.two !== undefined ? scenario.seats_per_hold.two : 0.3;

export const options = {
  scenarios: {
    hold: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '120s',
};

http.setResponseCallback(http.expectedStatuses(201, 409));
const OK_ADMIN = { responseCallback: http.expectedStatuses(200, 201), tags: { name: 'admin' } };

const holdSuccess = new Counter('hold_success');
const holdSeatTaken = new Counter('hold_seat_taken');
const holdActiveExists = new Counter('hold_active_exists');
const holdError = new Counter('hold_error');
const holdDuration = new Trend('hold_duration_ms', true);
const oversellCount = new Gauge('oversell_count');
const heldSeats = new Gauge('held_seats');
const holdingReservations = new Gauge('holding_reservations');
const expireBacklog = new Gauge('expire_backlog_max_s');

export function setup() {
  const now = Date.now();
  const created = http.post(
    `${BASE_URL}/api/admin/showtimes`,
    JSON.stringify({ startsAt: new Date(now + 7 * 86400e3).toISOString(), openAt: new Date(now).toISOString() }),
    Object.assign({ headers: { 'Content-Type': 'application/json' } }, OK_ADMIN),
  );
  if (created.status !== 201) throw new Error(`create showtime failed: ${created.status} ${created.body}`);
  const showtimeId = created.json('showtimeId');
  const seats = http.get(`${BASE_URL}/api/showtimes/${showtimeId}/seats`, OK_ADMIN).json();
  const allPool = seats.map((s) => s.seatId);
  // Hot pool: the best seats = lowest seat ids within VIP (first VIP rows).
  const hotPool = seats.filter((s) => s.grade === 'VIP').map((s) => s.seatId).sort((a, b) => a - b).slice(0, HOT_POOL);
  return { showtimeId, allPool, hotPool };
}

function pickSeats(data) {
  // Both seats of a 2-seat request come from the same pool.
  const seatIds = data.hotPool.length > 0 && Math.random() < HOT_RATIO ? data.hotPool : data.allPool;
  const first = seatIds[Math.floor(Math.random() * seatIds.length)];
  if (Math.random() >= TWO_SEAT_SHARE) return [first];
  let second = first;
  while (second === first) second = seatIds[Math.floor(Math.random() * seatIds.length)];
  return [first, second];
}

export default function (data) {
  const memberId = __VU * 1_000_000 + __ITER;
  const res = http.post(
    `${BASE_URL}/api/showtimes/${data.showtimeId}/holds`,
    JSON.stringify({ seatIds: pickSeats(data) }),
    {
      headers: { 'Content-Type': 'application/json', 'X-Member-Id': String(memberId), 'X-Hold-Strategy': STRATEGY },
      tags: { name: 'hold', strategy: STRATEGY },
    },
  );
  if (res.status === 0) {
    holdError.add(1); // network error: excluded from hold_p99_ms by definition
    return;
  }
  holdDuration.add(res.timings.duration, { strategy: STRATEGY });
  if (res.status === 201) {
    holdSuccess.add(1);
  } else if (res.status === 409) {
    const code = res.json('code');
    if (code === 'SEAT_TAKEN') holdSeatTaken.add(1);
    else if (code === 'ACTIVE_RESERVATION_EXISTS') holdActiveExists.add(1);
    else holdError.add(1);
  } else {
    holdError.add(1);
  }
}

export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/admin/showtimes/${data.showtimeId}/integrity`, OK_ADMIN);
  const i = res.json();
  oversellCount.add(i.oversell_count);
  heldSeats.add(i.held_seats);
  holdingReservations.add(i.holding_reservations);
  expireBacklog.add(i.expire_backlog_max_s);
}

function count(data, name) {
  return data.metrics[name] ? data.metrics[name].values.count : 0;
}

function gauge(data, name) {
  return data.metrics[name] ? data.metrics[name].values.value : null;
}

export function handleSummary(data) {
  const success = count(data, 'hold_success');
  const taken = count(data, 'hold_seat_taken');
  const active = count(data, 'hold_active_exists');
  const errors = count(data, 'hold_error');
  const total = success + taken + active + errors;
  const trend = data.metrics.hold_duration_ms ? data.metrics.hold_duration_ms.values : {};
  const ratio = (n) => (total > 0 ? n / total : null); // metrics.md rule 3: no denominator -> n/a
  const result = {
    strategy: STRATEGY,
    scenario: SCENARIO,
    base_url: BASE_URL,
    peak_rps: PEAK_RPS,
    duration_s: DURATION_S,
    hot_ratio: HOT_RATIO,
    hot_pool: HOT_POOL,
    hold_ttl: HOLD_TTL, // from the scenario `app:` block; the API must have been started with it
    started_at: new Date(Date.now() - data.state.testRunDurationMs).toISOString(),
    hold_p99_ms: trend['p(99)'] ?? null,
    hold_p50_ms: trend.med ?? null,
    // Successes (throughput) and all hold requests (attempts) over the configured arrival window (stages total).
    hold_throughput_rps: DURATION_S > 0 ? success / DURATION_S : null,
    hold_attempt_rps: DURATION_S > 0 ? total / DURATION_S : null,
    hold_success_rate: ratio(success),
    hold_conflict_rate: ratio(taken),
    hold_requests: total,
    hold_success: success,
    hold_seat_taken: taken,
    hold_active_exists: active,
    hold_error: errors,
    oversell_count: gauge(data, 'oversell_count'),
    held_seats: gauge(data, 'held_seats'),
    holding_reservations: gauge(data, 'holding_reservations'),
    expire_backlog_max_s: gauge(data, 'expire_backlog_max_s'),
    http_req_failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
  };
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`${RESULTS_DIR}/m1-${STRATEGY}-${stamp}.json`]: JSON.stringify(result, null, 2),
    stdout: `${textSummary(data, { indent: ' ', enableColors: false })}\n`,
  };
}
