/**
 * 05_expired_token.js
 *
 * 만료 토큰 거부 테스트
 *
 * 시나리오:
 *  1. 이벤트 ACTIVE 확인
 *  2. 유저 대기열 입장 → 토큰 발급 대기
 *  3. 관리자 API로 Redis 토큰 키 강제 삭제 (300초 TTL 만료 시뮬레이션)
 *  4. 만료된 토큰으로 claim 요청 → 401 UNAUTHORIZED 검증
 *  5. 유효한 토큰으로 claim 요청 → 200 성공 검증 (비교군)
 *
 * 실제 TTL 만료(300초)를 기다리지 않고 Admin API("/api/v1/admin/token" DELETE)를
 * 사용해 즉시 만료 상황을 재현한다.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, EVENT_ID, waitForEventActive, waitForAllTokens } from './shared/helpers.js';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        'token_errors': ['count==0'],
    },
};

const tokenErrors = new Counter('token_errors');

const HEADERS = { headers: { 'Content-Type': 'application/json' } };

function assert(name, cond) {
    if (!cond) {
        console.error(`FAIL: ${name}`);
        tokenErrors.add(1);
    } else {
        console.log(`PASS: ${name}`);
    }
}

export function setup() {
    // 이벤트가 ACTIVE인지 확인 (아니면 최대 120초 대기)
    const active = waitForEventActive(EVENT_ID, 120);
    if (!active) throw new Error('이벤트가 ACTIVE 상태가 아닙니다. 04_lifecycle_boundary.js 실행 후 force-activate 필요.');

    // 테스트용 유저 2명: expiredUser(만료 시뮬레이션), validUser(비교군)
    const expiredUserId = 77001;
    const validUserId   = 77002;

    // 대기열 초기화 (이전 테스트 잔여 데이터 제거)
    http.post(`${BASE_URL}/api/v1/admin/reset`);

    // force-activate: reset 이후 이벤트가 재활성화 안 되므로 다시 활성화
    http.post(`${BASE_URL}/api/v1/admin/force-activate/${EVENT_ID}`);

    // 두 유저 입장
    http.post(`${BASE_URL}/api/v1/queue/enter`,
        JSON.stringify({ userId: expiredUserId, eventId: EVENT_ID }), HEADERS);
    http.post(`${BASE_URL}/api/v1/queue/enter`,
        JSON.stringify({ userId: validUserId,   eventId: EVENT_ID }), HEADERS);

    // 토큰 발급 대기
    const tokens = waitForAllTokens([expiredUserId, validUserId], EVENT_ID, 15);
    const [expiredToken, validToken] = tokens;

    if (!expiredToken || !validToken) {
        throw new Error(`토큰 발급 실패: expired=${expiredToken}, valid=${validToken}`);
    }

    return { expiredUserId, expiredToken, validUserId, validToken };
}

export default function (data) {
    const { expiredUserId, expiredToken, validUserId, validToken } = data;

    // ── 1. 만료 시뮬레이션: Redis 토큰 키 삭제 ──────────────────────────────
    console.log('\n====== Step 1: 토큰 만료 시뮬레이션 ======');
    const expireRes = http.del(
        `${BASE_URL}/api/v1/admin/token?eventId=${EVENT_ID}&token=${expiredToken}`
    );
    assert('토큰 만료 처리 성공(200)', expireRes.status === 200);

    // ── 2. 만료된 토큰으로 claim → 401 기대 ─────────────────────────────────
    console.log('\n====== Step 2: 만료 토큰 claim → 401 검증 ======');
    const expiredClaim = http.post(
        `${BASE_URL}/api/v1/claim`,
        JSON.stringify({ userId: expiredUserId, eventId: EVENT_ID,
                         token: expiredToken, productId: 1 }),
        HEADERS
    );
    check(expiredClaim, { '만료 토큰 → 401 UNAUTHORIZED': (r) => r.status === 401 });
    assert('만료 토큰 claim 거부(401)', expiredClaim.status === 401);

    // ── 3. 유효한 토큰으로 claim → 200 또는 409(재고소진) 기대 ────────────
    console.log('\n====== Step 3: 유효 토큰 claim → 200/409 검증 ======');
    const validClaim = http.post(
        `${BASE_URL}/api/v1/claim`,
        JSON.stringify({ userId: validUserId, eventId: EVENT_ID,
                         token: validToken, productId: 1 }),
        HEADERS
    );
    check(validClaim, {
        '유효 토큰 → claim 처리됨(200 또는 409)':
            (r) => r.status === 200 || r.status === 409,
    });
    assert('유효 토큰 claim 처리됨', validClaim.status === 200 || validClaim.status === 409);

    console.log(`\n만료 토큰 응답: ${expiredClaim.status} ${expiredClaim.body}`);
    console.log(`유효 토큰 응답: ${validClaim.status} ${validClaim.body}`);
    console.log('\n====== 테스트 완료 ======');
}
