// M1 single-seat burst: VUS members hammer ONE seat; every winner releases at once so the next collision can
// happen. Counts how often two reservations held the seat at the same time (`oversell_occurred_count`).
//
//   STRATEGY    required: NAIVE | FOR_UPDATE | SKIP_LOCKED | CONDITIONAL_UPDATE | REDIS_LUA
//               (the API must run with OPENRUN_HOLD_ALLOW_OVERRIDE=true)
//   BASE_URL    default http://localhost:8080
//   VUS         default 100
//   DURATION_S  default 20
//   RESULTS_DIR default load/results (relative to the k6 working directory = repo root)
import http from 'k6/http';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const STRATEGY = __ENV.STRATEGY;
if (!STRATEGY) throw new Error('STRATEGY is required');
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const DURATION_S = Number(__ENV.DURATION_S || 20);
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load/results';

export const options = {
  scenarios: {
    burst: { executor: 'constant-vus', vus: VUS, duration: `${DURATION_S}s` },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '120s',
};

http.setResponseCallback(http.expectedStatuses(201, 204, 409));
const OK_ADMIN = { responseCallback: http.expectedStatuses(200, 201), tags: { name: 'admin' } };

const burstSuccess = new Counter('burst_success');
const burstSeatTaken = new Counter('burst_seat_taken');
const burstError = new Counter('burst_error');
// Same values as http_req_duration{name:hold}, kept as its own metric so the summary always carries p(99).
const burstHold = new Trend('burst_hold_duration_ms', true);
const oversellOccurred = new Gauge('burst_oversell_occurred');
const oversellRemaining = new Gauge('burst_oversell_remaining');
const oversellPairs = new Gauge('burst_oversell_pairs');

export function setup() {
  const now = Date.now();
  const created = http.post(
    `${BASE_URL}/api/admin/showtimes`,
    JSON.stringify({ startsAt: new Date(now + 7 * 86400e3).toISOString(), openAt: new Date(now).toISOString() }),
    Object.assign({ headers: { 'Content-Type': 'application/json' } }, OK_ADMIN),
  );
  if (created.status !== 201) throw new Error(`create showtime failed: ${created.status} ${created.body}`);
  const vip = created.json('seatIds.VIP').slice().sort((a, b) => a - b);
  return { showtimeId: created.json('showtimeId'), targetSeat: vip[0] };
}

export default function (data) {
  const memberId = String(__VU * 1_000_000 + __ITER);
  const res = http.post(
    `${BASE_URL}/api/showtimes/${data.showtimeId}/holds`,
    JSON.stringify({ seatIds: [data.targetSeat] }),
    {
      headers: { 'Content-Type': 'application/json', 'X-Member-Id': memberId, 'X-Hold-Strategy': STRATEGY },
      tags: { name: 'hold', strategy: STRATEGY },
    },
  );
  if (res.status !== 0) burstHold.add(res.timings.duration);
  if (res.status === 201) {
    burstSuccess.add(1);
    const del = http.del(`${BASE_URL}/api/reservations/${res.json('reservationId')}`, null, {
      headers: { 'X-Member-Id': memberId },
      tags: { name: 'release', strategy: STRATEGY },
    });
    if (del.status !== 204) burstError.add(1);
  } else if (res.status === 409 && res.json('code') === 'SEAT_TAKEN') {
    burstSeatTaken.add(1);
  } else {
    burstError.add(1);
  }
}

export function teardown(data) {
  const i = http.get(`${BASE_URL}/api/admin/showtimes/${data.showtimeId}/integrity`, OK_ADMIN).json();
  oversellOccurred.add(i.oversell_occurred_count);
  oversellRemaining.add(i.oversell_count);
  oversellPairs.add(i.oversell_overlap_pairs);
}

function count(data, name) {
  return data.metrics[name] ? data.metrics[name].values.count : 0;
}

function gauge(data, name) {
  return data.metrics[name] ? data.metrics[name].values.value : null;
}

export function handleSummary(data) {
  const trend = data.metrics.burst_hold_duration_ms ? data.metrics.burst_hold_duration_ms.values : {};
  const result = {
    strategy: STRATEGY,
    base_url: BASE_URL,
    vus: VUS,
    duration_s: DURATION_S,
    started_at: new Date(Date.now() - data.state.testRunDurationMs).toISOString(),
    burst_success: count(data, 'burst_success'),
    burst_seat_taken: count(data, 'burst_seat_taken'),
    burst_error: count(data, 'burst_error'),
    burst_oversell_occurred: gauge(data, 'burst_oversell_occurred'),
    burst_oversell_remaining: gauge(data, 'burst_oversell_remaining'),
    burst_oversell_pairs: gauge(data, 'burst_oversell_pairs'),
    burst_p99_ms: trend['p(99)'] ?? null,
    http_req_failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
  };
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`${RESULTS_DIR}/m1-burst-${STRATEGY}-${stamp}.json`]: JSON.stringify(result, null, 2),
    stdout: `${textSummary(data, { indent: ' ', enableColors: false })}\n`,
  };
}
