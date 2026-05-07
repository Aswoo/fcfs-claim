import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = 'http://localhost:8081';

const successCount = new Counter('enter_success');
const failCount = new Counter('enter_fail');

export const options = {
  stages: [
    { duration: '5s', target: 50 },
    { duration: '10s', target: 100 },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const userId = __VU * 1000 + __ITER;
  const eventId = 1;

  const res = http.post(
    `${BASE_URL}/api/v1/queue/enter`,
    JSON.stringify({ userId, eventId }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const ok = check(res, {
    '상태코드 200': (r) => r.status === 200,
    '응답에 rank 포함': (r) => JSON.parse(r.body).data?.rank !== undefined,
  });

  if (ok) successCount.add(1);
  else failCount.add(1);

  sleep(0.1);
}
