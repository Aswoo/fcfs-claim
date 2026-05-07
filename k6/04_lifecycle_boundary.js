/**
 * 04_lifecycle_boundary.js
 *
 * 이벤트 생명주기 경계 테스트
 *
 * 검증 시나리오:
 *  Phase 1 (SCHEDULED) : 대기열 입장 → rank 반환 O, 토큰 미발급 확인
 *  Phase 2 (ACTIVE)    : force-activate 후 → 기존 대기 유저 토큰 발급 확인
 *  Phase 3 (ENDED)     : force-end 후 → 신규 입장자 토큰 미발급 확인
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, EVENT_ID, waitForAllTokens } from './shared/helpers.js';

// 단일 반복으로 순차 실행
export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        'lifecycle_errors': ['count==0'],
    },
};

const lifecycleErrors = new Counter('lifecycle_errors');

const HEADERS = { headers: { 'Content-Type': 'application/json' } };

// ─── 헬퍼 ────────────────────────────────────────────────────────────────────

function enter(userId) {
    return http.post(
        `${BASE_URL}/api/v1/queue/enter`,
        JSON.stringify({ userId, eventId: EVENT_ID }),
        HEADERS
    );
}

function getStatus(userId) {
    return http.get(`${BASE_URL}/api/v1/queue/status?userId=${userId}&eventId=${EVENT_ID}`);
}

function assert(name, cond) {
    if (!cond) {
        console.error(`FAIL: ${name}`);
        lifecycleErrors.add(1);
    } else {
        console.log(`PASS: ${name}`);
    }
}

// ─── 테스트 본문 ─────────────────────────────────────────────────────────────

export default function () {

    // ── 사전 준비 ─────────────────────────────────────────────────────────────
    console.log('\n====== 사전 준비: 상태 초기화 ======');
    http.post(`${BASE_URL}/api/v1/admin/reset`);
    http.post(`${BASE_URL}/api/v1/admin/force-schedule/${EVENT_ID}`);  // SCHEDULED로 리셋

    // 이벤트가 SCHEDULED인지 확인
    const initStatus = JSON.parse(
        http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}/status`).body
    ).data?.status;
    assert('초기 상태가 SCHEDULED', initStatus === 'SCHEDULED');


    // ── Phase 1: SCHEDULED 상태에서 대기열 입장 ──────────────────────────────
    console.log('\n====== Phase 1: SCHEDULED – 입장 O, 토큰 미발급 ======');

    const scheduledUsers = [10001, 10002, 10003];
    for (const uid of scheduledUsers) {
        const res = enter(uid);
        const rank = JSON.parse(res.body).data?.rank;
        assert(`userId=${uid} 입장 성공(rank 존재)`, res.status === 200 && rank !== undefined);
    }

    // 3초 대기 – processQueue 가 3번 돌아도 SCHEDULED라 아무것도 발급하지 않아야 함
    sleep(3);

    for (const uid of scheduledUsers) {
        const body = JSON.parse(getStatus(uid).body).data;
        assert(`userId=${uid} 토큰 미발급(SCHEDULED 중)`, !body?.isReady);
    }


    // ── Phase 2: force-activate → 기존 대기자 토큰 발급 ─────────────────────
    console.log('\n====== Phase 2: ACTIVE 전환 → 기존 대기자 토큰 발급 ======');

    http.post(`${BASE_URL}/api/v1/admin/force-activate/${EVENT_ID}`);

    const activeStatus = JSON.parse(
        http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}/status`).body
    ).data?.status;
    assert('force-activate 후 ACTIVE', activeStatus === 'ACTIVE');

    // processQueue가 1초마다 돌므로 최대 15초 대기
    const tokens = waitForAllTokens(scheduledUsers, EVENT_ID, 15);
    for (let i = 0; i < scheduledUsers.length; i++) {
        assert(`userId=${scheduledUsers[i]} 토큰 발급됨(ACTIVE 전환 후)`, tokens[i] !== null);
    }


    // ── Phase 3: force-end → 신규 입장자 토큰 미발급 ────────────────────────
    console.log('\n====== Phase 3: ENDED 전환 → 신규 입장자 토큰 미발급 ======');

    http.post(`${BASE_URL}/api/v1/admin/force-end/${EVENT_ID}`);

    const endedStatus = JSON.parse(
        http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}/status`).body
    ).data?.status;
    assert('force-end 후 ENDED', endedStatus === 'ENDED');

    // 이벤트 종료 후 새 유저 입장
    const lateUser = 99999;
    const lateEnter = enter(lateUser);
    assert(`종료 후 입장 요청 수신(200)`, lateEnter.status === 200);

    // 5초 대기 – ENDED라 processQueue가 이 이벤트를 처리하지 않음
    sleep(5);

    const lateBody = JSON.parse(getStatus(lateUser).body).data;
    assert('종료 후 입장자 토큰 미발급', !lateBody?.isReady);

    console.log('\n====== 테스트 완료 ======');
}
