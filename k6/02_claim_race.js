import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, EVENT_ID, waitForEventActive, waitForAllTokens } from './shared/helpers.js';

const TOTAL_USERS = 30;

const claimSuccess = new Counter('claim_success');
const claimSoldOut = new Counter('claim_sold_out');
const claimDuplicate = new Counter('claim_duplicate');
const claimError = new Counter('claim_error');

export const options = {
  vus: TOTAL_USERS,
  iterations: TOTAL_USERS,
  thresholds: {
    'claim_error': ['count==0'],
  },
};

export function setup() {
  console.log('=== 1단계: 테스트 초기화 ===');
  const resetRes = http.post(`${BASE_URL}/api/v1/admin/reset`);
  check(resetRes, { '초기화 성공': (r) => r.status === 200 });

  console.log('=== 1.5단계: 이벤트 ACTIVE 대기 ===');
  const active = waitForEventActive(EVENT_ID, 120);
  if (!active) throw new Error('이벤트가 ACTIVE 상태로 전환되지 않았습니다. 서버 로그를 확인하세요.');

  console.log(`=== 2단계: ${TOTAL_USERS}명 대기열 입장 ===`);
  const userIds = [];
  for (let i = 1; i <= TOTAL_USERS; i++) {
    const userId = 900000 + i;
    userIds.push(userId);
    http.post(
      `${BASE_URL}/api/v1/queue/enter`,
      JSON.stringify({ userId, eventId: EVENT_ID }),
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  console.log('=== 3단계: 토큰 발급 대기 (최대 15초) ===');
  const tokens = waitForAllTokens(userIds, EVENT_ID, 15);
  console.log(`토큰 수집 완료: ${tokens.filter(t => t !== null).length}/${TOTAL_USERS}명`);

  return { userIds, tokens };
}

export default function (data) {
  const idx = __VU - 1;
  const userId = data.userIds[idx];
  const token = data.tokens[idx];

  if (!token) {
    console.warn(`VU ${__VU}: 토큰 없음, 스킵`);
    return;
  }

  const productId = (idx % 4) + 1;

  const res = http.post(
    `${BASE_URL}/api/v1/claim`,
    JSON.stringify({ userId, eventId: EVENT_ID, token, productId }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (res.status === 200) {
    claimSuccess.add(1);
    check(res, { '수령 성공 (200)': () => true });
  } else if (res.status === 409) {
    // message 필드: Spring Boot의 include-message=always 설정 시 포함됨
    const body = JSON.parse(res.body);
    const msg = body.message ?? body.detail ?? '';
    if (msg.includes('재고') || msg.includes('소진')) {
      claimSoldOut.add(1);
      check(res, { '재고 소진 (409)': () => true });
    } else if (msg.includes('이미') || msg.includes('중복')) {
      claimDuplicate.add(1);
      check(res, { '중복 수령 (409)': () => true });
    } else {
      // 메시지로 구분 불가 시 sold_out으로 처리 (이 테스트는 userId 전부 유니크)
      claimSoldOut.add(1);
      check(res, { '재고 소진 (409)': () => true });
    }
  } else {
    claimError.add(1);
    console.error(`VU ${__VU} 예상치 못한 오류: ${res.status} ${res.body}`);
  }
}

export function teardown() {
  console.log('\n========== 결과 확인 ==========');
  console.log('claim_success   → 성공 (최대 20)');
  console.log('claim_sold_out  → 재고 소진 실패');
  console.log('claim_duplicate → 중복 수령 실패');
  console.log('claim_error     → 예상치 못한 오류 (반드시 0이어야 함)');
}
