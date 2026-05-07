import http from 'k6/http';
import { sleep } from 'k6';

export const BASE_URL = 'http://localhost:8081';
export const EVENT_ID  = 1;

/**
 * 이벤트가 ACTIVE가 될 때까지 폴링한다.
 * @param {number} eventId
 * @param {number} maxSeconds - 최대 대기 시간 (기본 120초)
 * @returns {boolean} ACTIVE가 됐으면 true, 타임아웃이면 false
 */
export function waitForEventActive(eventId = EVENT_ID, maxSeconds = 120) {
    const deadline = Date.now() + maxSeconds * 1000;
    while (Date.now() < deadline) {
        const res = http.get(`${BASE_URL}/api/v1/events/${eventId}/status`);
        if (res.status === 200) {
            const status = JSON.parse(res.body).data?.status;
            if (status === 'ACTIVE') return true;
            if (status === 'ENDED')  return false;  // 이미 종료됨
        }
        sleep(2);
    }
    console.error(`이벤트 ${eventId}: ${maxSeconds}초 내 ACTIVE 전환 실패`);
    return false;
}

/**
 * 지정한 유저 목록 전원의 토큰이 발급될 때까지 폴링한다.
 * @param {number[]} userIds
 * @param {number} eventId
 * @param {number} maxSeconds
 * @returns {(string|null)[]} 인덱스 순서로 토큰 배열 (타임아웃된 유저는 null)
 */
export function waitForAllTokens(userIds, eventId = EVENT_ID, maxSeconds = 15) {
    const tokens   = new Array(userIds.length).fill(null);
    const deadline = Date.now() + maxSeconds * 1000;

    while (Date.now() < deadline) {
        let allReady = true;
        for (let i = 0; i < userIds.length; i++) {
            if (tokens[i] !== null) continue;

            const res = http.get(
                `${BASE_URL}/api/v1/queue/status?userId=${userIds[i]}&eventId=${eventId}`
            );
            if (res.status === 200) {
                const data = JSON.parse(res.body).data;
                if (data?.isReady && data?.token) {
                    tokens[i] = data.token;
                } else {
                    allReady = false;
                }
            }
        }
        if (allReady) break;
        sleep(1);
    }
    return tokens;
}
