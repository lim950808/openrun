// M2-3 refresh storm (docs/requirements.md M2-3, metrics in docs/metrics.md "M2 대기열"): every VU is one member who
// enters the queue once and then polls GET /queue/me every `refresh_storm_interval_seconds` (0.2 s).
//
//   BASE_URL      default http://localhost:8080
//   SHOWTIME_ID   showtime to storm; when unset, setup creates one opening OPEN_DELAY_S from now
//   OPEN_DELAY_S  default 20 (only when SHOWTIME_ID is unset)
//   VUS           default 1000
//   DURATION_S    default 30
//   MEMBER_OFFSET default 1000000000; member id = MEMBER_OFFSET + __VU
//   SCENARIO      repo-relative yaml path, default load/scenarios/weekly-default.yaml
//   RESULTS_DIR   default load/results (relative to the k6 working directory = repo root)
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import yaml from './vendor/js-yaml.mjs';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'load/scenarios/weekly-default.yaml';
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load/results';
const VUS = Number(__ENV.VUS || 1000);
const DURATION_S = Number(__ENV.DURATION_S || 30);
const MEMBER_OFFSET = Number(__ENV.MEMBER_OFFSET || 1_000_000_000);
const OPEN_DELAY_S = Number(__ENV.OPEN_DELAY_S || 20);

// open() resolves relative paths against this script's directory (load/k6), so climb to the repo root.
const scenario = yaml.load(open(SCENARIO.startsWith('/') ? SCENARIO : `../../${SCENARIO}`));
const INTERVAL_S = scenario.refresh_storm_interval_seconds;

export const options = {
  scenarios: {
    storm: { executor: 'constant-vus', vus: VUS, duration: `${DURATION_S}s`, gracefulStop: '5s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '120s',
  thresholds: {
    // rank_api_p99_ms and rank_drift_count targets (docs/metrics.md).
    'http_req_duration{name:queue_me}': ['p(99)<100'],
    rank_drift_count: ['count==0'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));
const ADMIN = { headers: { 'Content-Type': 'application/json' }, tags: { name: 'admin' } };

const rankDrift = new Counter('rank_drift_count');
const polls = new Counter('queue_polls');
const notInQueue = new Counter('queue_not_in_queue');
const admittedSeen = new Counter('queue_admitted_seen');
const queueError = new Counter('queue_error');

export function setup() {
  if (__ENV.SHOWTIME_ID) return { showtimeId: Number(__ENV.SHOWTIME_ID) };
  const now = Date.now();
  const created = http.post(
    `${BASE_URL}/api/admin/showtimes`,
    JSON.stringify({ startsAt: new Date(now + 7 * 86400e3).toISOString(), openAt: new Date(now + OPEN_DELAY_S * 1000).toISOString() }),
    ADMIN,
  );
  if (created.status !== 201) throw new Error(`create showtime failed: ${created.status} ${created.body}`);
  return { showtimeId: created.json('showtimeId') };
}

// Per-VU state (each VU is one member).
let visitorToken = null;
let lastAhead = null; // null until the first WAITING answer; -1 once admitted

function enter(data) {
  const res = http.post(`${BASE_URL}/api/showtimes/${data.showtimeId}/queue`, null, {
    headers: { 'X-Member-Id': String(MEMBER_OFFSET + __VU) },
    tags: { name: 'queue_enter' },
  });
  if (res.status !== 200 && res.status !== 201) {
    queueError.add(1);
    return;
  }
  if (res.json('status') === 'ADMITTED') {
    lastAhead = -1;
    return;
  }
  visitorToken = res.json('visitorToken');
  observe(res.json('ahead'));
}

function observe(ahead) {
  // Waiting after admitted, or a larger ahead than last time, is a drift (metrics.md rank_drift_count).
  if (lastAhead !== null && (lastAhead === -1 || ahead > lastAhead)) rankDrift.add(1);
  lastAhead = ahead;
}

export default function (data) {
  if (visitorToken === null && lastAhead !== -1) {
    enter(data);
    sleep(INTERVAL_S);
    return;
  }
  if (visitorToken === null) {
    // Admitted at entry: nothing to poll with.
    sleep(INTERVAL_S);
    return;
  }
  const res = http.get(`${BASE_URL}/api/showtimes/${data.showtimeId}/queue/me`, {
    headers: { Authorization: `Bearer ${visitorToken}` },
    tags: { name: 'queue_me' },
  });
  polls.add(1);
  if (res.status !== 200) {
    queueError.add(1);
  } else {
    const status = res.json('status');
    if (status === 'WAITING') observe(res.json('ahead'));
    else if (status === 'ADMITTED') {
      if (lastAhead !== -1) admittedSeen.add(1);
      lastAhead = -1;
    } else {
      notInQueue.add(1);
    }
  }
  sleep(INTERVAL_S);
}

function count(data, name) {
  return data.metrics[name] ? data.metrics[name].values.count : 0;
}

export function handleSummary(data) {
  const me = data.metrics['http_req_duration{name:queue_me}'];
  const result = {
    scenario: SCENARIO,
    base_url: BASE_URL,
    showtime_id: data.setup_data ? data.setup_data.showtimeId : null,
    vus: VUS,
    duration_s: DURATION_S,
    poll_interval_s: INTERVAL_S,
    started_at: new Date(Date.now() - data.state.testRunDurationMs).toISOString(),
    rank_api_p99_ms: me ? me.values['p(99)'] : null,
    rank_api_p50_ms: me ? me.values.med : null,
    rank_drift_count: count(data, 'rank_drift_count'),
    polls: count(data, 'queue_polls'),
    poll_rps: count(data, 'queue_polls') / DURATION_S,
    not_in_queue: count(data, 'queue_not_in_queue'),
    admitted_seen: count(data, 'queue_admitted_seen'),
    queue_error: count(data, 'queue_error'),
    http_req_failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
  };
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`${RESULTS_DIR}/m2-storm-${stamp}.json`]: JSON.stringify(result, null, 2),
    stdout: `${textSummary(data, { indent: ' ', enableColors: false })}\n`,
  };
}
