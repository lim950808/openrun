// M2 weekly open run bot (docs/requirements.md §2, M2-1~M2-4; parameters from the scenario yaml, ADR-0002).
// One iteration = one member's journey: enter the queue -> poll (may abandon) -> admitted -> select seats -> hold
// -> (may abandon payment) -> pay (M3, ADR-0007): POST /payments, Fake PG authorization with a fault drawn from
// `fake_pg.fault_mix`, POST /payments/confirm. The API must run with OPENRUN_PAYMENT_PG=fake.
//
// Arrival: the yaml stage shape (`at` wall clock, `target_rps`) is scaled in time to DURATION_S and in height so the
// whole run brings MEMBERS members. Member journeys are long, so the yaml's absolute rps (which includes polling)
// is not used as the iteration rate.
//
//   BASE_URL               default http://localhost:8080
//   SHOWTIME_ID            showtime to run against; when unset, setup creates one opening OPEN_DELAY_S from now
//   OPEN_DELAY_S           default 20 (only when SHOWTIME_ID is unset)
//   DURATION_S             arrival window, default 120
//   MEMBERS                members arriving over the window, default yaml virtual_users
//   MEMBER_OFFSET          default 2000000000; member id = MEMBER_OFFSET + global iteration number
//   ADMISSIONS_PER_SECOND  default yaml queue.admissions_per_second; set to the API's value (expected wait estimate)
//   SCENARIO               repo-relative yaml path, default load/scenarios/weekly-default.yaml
//   RESULTS_DIR            default load/results
//   AMOUNT_MISMATCH_RATE   default 0.01; share of payments that first send a confirm with amount + 1 (expects 400)
//
//   k6 run -e BASE_URL=http://localhost:8080 -e SHOWTIME_ID=1 load/k6/weekly-openrun.js
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import yaml from './vendor/js-yaml.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'load/scenarios/weekly-default.yaml';
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load/results';
const OPEN_DELAY_S = Number(__ENV.OPEN_DELAY_S || 20);
const DURATION_S = Number(__ENV.DURATION_S || 120);
const MEMBER_OFFSET = Number(__ENV.MEMBER_OFFSET || 2_000_000_000);

const scenario = yaml.load(open(SCENARIO.startsWith('/') ? SCENARIO : `../../${SCENARIO}`));
const MEMBERS = Number(__ENV.MEMBERS || scenario.virtual_users);
const ADMISSIONS_PER_SECOND = Number(__ENV.ADMISSIONS_PER_SECOND || scenario.queue.admissions_per_second);

// "HH:MM" stage points -> k6 stages. Stage i lasts from point i-1 to point i; the first point is the start.
const minutes = (hhmm) => {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
};
const points = scenario.arrival.stages.map((s) => ({ t: minutes(s.at) * 60, rps: s.target_rps }));
const yamlTotalS = points[points.length - 1].t - points[0].t;
// Area under the piecewise-linear shape (in yaml units) -> height scale that yields MEMBERS arrivals.
let area = 0;
for (let i = 1; i < points.length; i += 1) area += ((points[i].rps + points[i - 1].rps) / 2) * (points[i].t - points[i - 1].t);
const timeScale = DURATION_S / yamlTotalS;
const heightScale = MEMBERS / (area * timeScale);
const stages = points.slice(1).map((p, i) => ({
  duration: `${Math.max(1, Math.round((p.t - points[i].t) * timeScale * 1000))}ms`,
  target: Math.max(0, Math.round(p.rps * heightScale)),
}));
const startRate = Math.round(points[0].rps * heightScale);

const SEAT_CHOICE = scenario.seat_choice || { hot_ratio: 0, hot_pool: 0 };
const FAKE_PG = scenario.fake_pg || { fault_mix: { NONE: 1 }, delay_seconds: 2 };
const FAULTS = Object.keys(FAKE_PG.fault_mix);
const AMOUNT_MISMATCH_RATE = Number(__ENV.AMOUNT_MISMATCH_RATE ?? 0.01);

export const options = {
  scenarios: {
    openrun: {
      executor: 'ramping-arrival-rate',
      startRate,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(MEMBERS, 500),
      maxVUs: MEMBERS,
      stages,
      // Journeys that started late still get to hold and pay (seat selection up to 90 s, payment ~60 s).
      gracefulStop: '240s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '300s',
  thresholds: {
    // docs/requirements.md §4: rank API p99 100 ms, hold API p99 200 ms with the queue. Also exposes the
    // tagged sub-metrics in the summary.
    'http_req_duration{name:queue_me}': ['p(99)<100'],
    'http_req_duration{name:hold}': ['p(99)<200'],
    'http_req_duration{name:payment_confirm}': ['max>=0'],
    rank_drift_count: ['count==0'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const entered = new Counter('queue_entered');
const abandonedByBot = new Counter('queue_abandoned_by_bot');
const admitted = new Counter('queue_admitted');
const reentered = new Counter('queue_reentered');
const rankDrift = new Counter('rank_drift_count');
const holdSuccess = new Counter('hold_success');
const seatTaken = new Counter('seat_taken');
const activeExists = new Counter('hold_active_exists');
const unauthorized = new Counter('unauthorized');
const holdError = new Counter('hold_error');
const queueError = new Counter('queue_error');
const waitSeconds = new Trend('queue_wait_s');
const paymentAbandoned = new Counter('payment_abandoned');
const paymentStarted = new Counter('payment_started');
const paymentError = new Counter('payment_error');
const confirmOk = new Counter('confirm_ok');
const confirmUnknown = new Counter('confirm_unknown');
const confirmDeclined = new Counter('confirm_declined');
const confirmError = new Counter('confirm_error');
const amountMismatchInjected = new Counter('amount_mismatch_injected');
const amountMismatchRejected = new Counter('amount_mismatch_rejected');
const faultInjected = Object.fromEntries(FAULTS.map((f) => [f, new Counter(`fault_${f}`)]));

function sample(dist) {
  if (dist.type === 'fixed') return dist.value;
  if (dist.type === 'uniform') return dist.min + Math.random() * (dist.max - dist.min);
  if (dist.type === 'normal') {
    // Box-Muller, truncated at 0.
    const z = Math.sqrt(-2 * Math.log(1 - Math.random())) * Math.cos(2 * Math.PI * Math.random());
    return Math.max(0, dist.mean + z * dist.sd);
  }
  throw new Error(`unknown distribution ${dist.type}`);
}

function pickFault() {
  let r = Math.random() * FAULTS.reduce((acc, f) => acc + FAKE_PG.fault_mix[f], 0);
  for (const f of FAULTS) {
    r -= FAKE_PG.fault_mix[f];
    if (r < 0) return f;
  }
  return FAULTS[FAULTS.length - 1];
}

function confirm(orderId, paymentKey, amount, headers, params) {
  return http.post(`${BASE_URL}/api/payments/confirm`, JSON.stringify({ orderId, paymentKey, amount }), Object.assign({ headers }, params));
}

/** Payment step (M3). Payment APIs check the reservation owner, not the access token (requirements §1). */
function pay(reservationId, memberId) {
  if (Math.random() < scenario.payment_abandon_probability) {
    paymentAbandoned.add(1);
    return;
  }
  sleep(sample(scenario.payment_seconds));
  const headers = Object.assign({ 'X-Member-Id': String(memberId) }, JSON_HEADERS);
  const start = http.post(`${BASE_URL}/api/reservations/${reservationId}/payments`, null, { headers, tags: { name: 'payment_start' } });
  if (start.status !== 200) {
    paymentError.add(1);
    return;
  }
  paymentStarted.add(1);
  const orderId = start.json('orderId');
  const amount = start.json('amount');
  const fault = pickFault();
  faultInjected[fault].add(1);
  const auth = http.post(
    `${BASE_URL}/api/fake-pg/payments`,
    JSON.stringify({ orderId, amount, fault, delaySeconds: FAKE_PG.delay_seconds }),
    { headers: JSON_HEADERS, tags: { name: 'fake_pg' } },
  );
  if (auth.status !== 200) {
    paymentError.add(1);
    return;
  }
  const paymentKey = auth.json('paymentKey');
  if (Math.random() < AMOUNT_MISMATCH_RATE) {
    amountMismatchInjected.add(1);
    const tampered = confirm(orderId, paymentKey, amount + 1, headers, {
      tags: { name: 'payment_confirm_tampered' },
      responseCallback: http.expectedStatuses(400),
    });
    if (tampered.status === 400) amountMismatchRejected.add(1);
  }
  const res = confirm(orderId, paymentKey, amount, headers, { tags: { name: 'payment_confirm' } });
  if (res.status === 202) confirmUnknown.add(1);
  else if (res.status === 200 && res.json('status') === 'CONFIRMED') confirmOk.add(1);
  else if (res.status === 200 && res.json('status') === 'FAILED') confirmDeclined.add(1);
  else confirmError.add(1);
}

function enter(showtimeId, memberId, tag) {
  return http.post(`${BASE_URL}/api/showtimes/${showtimeId}/queue`, null, {
    headers: { 'X-Member-Id': String(memberId) },
    tags: { name: tag },
  });
}

function poll(showtimeId, visitorToken) {
  return http.get(`${BASE_URL}/api/showtimes/${showtimeId}/queue/me`, {
    headers: { Authorization: `Bearer ${visitorToken}` },
    tags: { name: 'queue_me' },
  });
}

/** Seat ids need an access token, so setup walks the queue once with a dedicated member (counted in `entered`). */
export function setup() {
  let showtimeId = __ENV.SHOWTIME_ID ? Number(__ENV.SHOWTIME_ID) : null;
  if (showtimeId === null) {
    const now = Date.now();
    const created = http.post(
      `${BASE_URL}/api/admin/showtimes`,
      JSON.stringify({ startsAt: new Date(now + 7 * 86400e3).toISOString(), openAt: new Date(now + OPEN_DELAY_S * 1000).toISOString() }),
      { headers: JSON_HEADERS, tags: { name: 'admin' } },
    );
    if (created.status !== 201) throw new Error(`create showtime failed: ${created.status} ${created.body}`);
    showtimeId = created.json('showtimeId');
  }
  const setupMember = MEMBER_OFFSET - 1;
  let res = enter(showtimeId, setupMember, 'setup');
  if (res.status !== 200 && res.status !== 201) throw new Error(`setup entry failed: ${res.status} ${res.body}`);
  let token = res.json('status') === 'ADMITTED' ? res.json('accessToken') : null;
  const visitor = res.json('visitorToken');
  const deadline = Date.now() + 280e3;
  while (token === null && Date.now() < deadline) {
    sleep(1);
    res = poll(showtimeId, visitor);
    if (res.json('status') === 'ADMITTED') token = res.json('accessToken');
  }
  if (token === null) throw new Error('setup member was not admitted; is the showtime open?');
  const seats = http.get(`${BASE_URL}/api/showtimes/${showtimeId}/seats`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'setup' },
  }).json();
  const allPool = seats.map((s) => s.seatId);
  // Hot pool: the best seats = lowest seat ids within VIP (same rule as bench-m1-hold.js).
  const hotPool = seats.filter((s) => s.grade === 'VIP').map((s) => s.seatId).sort((a, b) => a - b).slice(0, SEAT_CHOICE.hot_pool);
  return { showtimeId, allPool, hotPool };
}

function pickSeats(data) {
  const pool = data.hotPool.length > 1 && Math.random() < SEAT_CHOICE.hot_ratio ? data.hotPool : data.allPool;
  const first = pool[Math.floor(Math.random() * pool.length)];
  let second = first;
  while (second === first) second = pool[Math.floor(Math.random() * pool.length)];
  return [first, second];
}

export default function (data) {
  const memberId = MEMBER_OFFSET + exec.scenario.iterationInTest;
  const macro = Math.random() < scenario.macro_ratio;
  const pollInterval = macro ? scenario.macro.polling_interval_seconds : scenario.polling_interval_seconds;
  const willAbandon = Math.random() < scenario.abandon_probability;
  const start = Date.now();

  let res = enter(data.showtimeId, memberId, 'queue_enter');
  if (res.status !== 200 && res.status !== 201) {
    queueError.add(1);
    return;
  }
  entered.add(1);
  let token = res.json('status') === 'ADMITTED' ? res.json('accessToken') : null;
  let visitor = res.json('visitorToken');
  let lastAhead = token === null ? res.json('ahead') : null;
  // Leave at a uniform point of the member's own expected wait (yaml abandon_probability semantics).
  const expectedWaitS = (lastAhead || 0) / ADMISSIONS_PER_SECOND;
  const leaveAt = willAbandon ? start + Math.random() * expectedWaitS * 1000 : Infinity;

  while (token === null) {
    if (Date.now() >= leaveAt) {
      abandonedByBot.add(1);
      return;
    }
    sleep(pollInterval);
    res = poll(data.showtimeId, visitor);
    if (res.status !== 200) {
      queueError.add(1);
      continue;
    }
    const status = res.json('status');
    if (status === 'WAITING') {
      const ahead = res.json('ahead');
      if (ahead > lastAhead) rankDrift.add(1);
      lastAhead = ahead;
    } else if (status === 'ADMITTED') {
      token = res.json('accessToken');
    } else {
      // NOT_IN_QUEUE: back of the line (ADR-0006 §2-4).
      reentered.add(1);
      res = enter(data.showtimeId, memberId, 'queue_enter');
      if (res.status !== 200 && res.status !== 201) {
        queueError.add(1);
        return;
      }
      if (res.json('status') === 'ADMITTED') token = res.json('accessToken');
      else {
        visitor = res.json('visitorToken');
        lastAhead = res.json('ahead');
      }
    }
  }
  admitted.add(1);
  waitSeconds.add((Date.now() - start) / 1000);

  sleep(macro ? sample(scenario.macro.seat_select_seconds) : sample(scenario.seat_select_seconds));
  const hold = http.post(
    `${BASE_URL}/api/showtimes/${data.showtimeId}/holds`,
    JSON.stringify({ seatIds: pickSeats(data) }),
    { headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS), tags: { name: 'hold' } },
  );
  if (hold.status === 201) {
    holdSuccess.add(1);
    pay(hold.json('reservationId'), memberId);
  } else if (hold.status === 401) unauthorized.add(1);
  else if (hold.status === 409 && hold.json('code') === 'SEAT_TAKEN') seatTaken.add(1);
  else if (hold.status === 409 && hold.json('code') === 'ACTIVE_RESERVATION_EXISTS') activeExists.add(1);
  else holdError.add(1);
}

function count(data, name) {
  return data.metrics[name] ? data.metrics[name].values.count : 0;
}

function p(data, name, stat) {
  return data.metrics[name] ? data.metrics[name].values[stat] ?? null : null;
}

export function handleSummary(data) {
  const result = {
    scenario: SCENARIO,
    base_url: BASE_URL,
    showtime_id: data.setup_data ? data.setup_data.showtimeId : null,
    members: MEMBERS,
    duration_s: DURATION_S,
    admissions_per_second: ADMISSIONS_PER_SECOND,
    stages,
    started_at: new Date(Date.now() - data.state.testRunDurationMs).toISOString(),
    queue_entered: count(data, 'queue_entered'),
    queue_abandoned_by_bot: count(data, 'queue_abandoned_by_bot'),
    queue_admitted: count(data, 'queue_admitted'),
    queue_reentered: count(data, 'queue_reentered'),
    rank_drift_count: count(data, 'rank_drift_count'),
    queue_wait_p50_s: p(data, 'queue_wait_s', 'med'),
    queue_wait_p99_s: p(data, 'queue_wait_s', 'p(99)'),
    rank_api_p99_ms: p(data, 'http_req_duration{name:queue_me}', 'p(99)'),
    hold_p99_ms: p(data, 'http_req_duration{name:hold}', 'p(99)'),
    hold_success: count(data, 'hold_success'),
    seat_taken: count(data, 'seat_taken'),
    hold_active_exists: count(data, 'hold_active_exists'),
    unauthorized: count(data, 'unauthorized'),
    hold_error: count(data, 'hold_error'),
    queue_error: count(data, 'queue_error'),
    payment_abandoned: count(data, 'payment_abandoned'),
    payment_started: count(data, 'payment_started'),
    payment_error: count(data, 'payment_error'),
    confirm_ok: count(data, 'confirm_ok'),
    confirm_unknown: count(data, 'confirm_unknown'),
    confirm_declined: count(data, 'confirm_declined'),
    confirm_error: count(data, 'confirm_error'),
    amount_mismatch_injected: count(data, 'amount_mismatch_injected'),
    amount_mismatch_rejected: count(data, 'amount_mismatch_rejected'),
    faults_injected: Object.fromEntries(FAULTS.map((f) => [f, count(data, `fault_${f}`)])),
    confirm_p99_ms: p(data, 'http_req_duration{name:payment_confirm}', 'p(99)'),
    http_req_failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
  };
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`${RESULTS_DIR}/m2-weekly-${stamp}.json`]: JSON.stringify(result, null, 2),
    stdout: `${textSummary(data, { indent: ' ', enableColors: false })}\n`,
  };
}
