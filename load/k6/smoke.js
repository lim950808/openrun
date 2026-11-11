// CI smoke: proves the API boots against compose `core` and answers fast. Not a benchmark.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 5,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/ping`);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'body status ok': (r) => r.json('status') === 'ok',
  });
}
