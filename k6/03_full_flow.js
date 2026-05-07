import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, EVENT_ID, waitForEventActive } from './shared/helpers.js';

const MAX_WAIT_SECONDS = 30;

export const options = {
  vus: 20,
  iterations: 20,   // 각 VU가 딱 1번만 실행 (재고 소진 후 루프 방지)
  thresholds: {
    http_req_duration: ['p(95)<1000'],
  },
};

export function setup() {
  console.log('=== 이벤트 ACTIVE 대기 중 ===');
  const active = waitForEventActive(EVENT_ID, 120);
  if (!active) throw new Error('이벤트가 ACTIVE 상태로 전환되지 않았습니다.');
  console.log('=== 이벤트 ACTIVE 확인, 테스트 시작 ===');
}

export default function () {
  const userId = __VU * 100000 + __ITER;

  // 1단계: 대기열 입장
  const enterRes = http.post(
    `${BASE_URL}/api/v1/queue/enter`,
    JSON.stringify({ userId, eventId: EVENT_ID }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (!check(enterRes, { '입장 성공': (r) => r.status === 200 })) return;

  const rank = JSON.parse(enterRes.body).data?.rank;
  console.log(`VU ${__VU}: 입장 완료, 순번 ${rank}`);

  // 2단계: 토큰 발급 대기
  let token = null;
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;

  while (Date.now() < deadline) {
    sleep(1);

    const statusRes = http.get(
      `${BASE_URL}/api/v1/queue/status?userId=${userId}&eventId=${EVENT_ID}`
    );

    if (statusRes.status === 200) {
      const data = JSON.parse(statusRes.body).data;
      if (data?.isReady && data?.token) {
        token = data.token;
        break;
      }
    }
  }

  if (!token) {
    console.warn(`VU ${__VU}: 대기 시간 초과`);
    return;
  }

  console.log(`VU ${__VU}: 토큰 발급 완료`);

  // 3단계: 클레임
  const productId = (__VU % 4) + 1;

  const claimRes = http.post(
    `${BASE_URL}/api/v1/claim`,
    JSON.stringify({ userId, eventId: EVENT_ID, token, productId }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(claimRes, {
    '클레임 성공(200)': (r) => r.status === 200,
    '재고소진(409)': (r) => r.status === 409,
  });

  console.log(`VU ${__VU}: 클레임 결과 ${claimRes.status}`);
}
