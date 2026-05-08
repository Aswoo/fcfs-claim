# k6 부하 테스트

## k6란?

JavaScript로 테스트 시나리오를 작성하면 수백 명의 가상 유저(VU)를 동시에 실행해서 서버가 버티는지 측정해주는 부하 테스트 도구다.

```
내가 직접 100명이서 동시에 버튼을 누를 수 없으니 → k6가 대신 해준다
```

### 설치

```bash
brew install k6
k6 version
```

---

## 테스트 파일 구성

```
k6/
├── shared/
│   └── helpers.js            # 공통 유틸 (waitForEventActive, waitForAllTokens)
├── 01_queue_stress.js        # 대기열 입장 스트레스 테스트
├── 02_claim_race.js          # 클레임 동시 경합 테스트 (핵심)
├── 03_full_flow.js           # 입장→대기→수령 전체 흐름
├── 04_lifecycle_boundary.js  # 이벤트 생명주기 경계 테스트
└── 05_expired_token.js       # 만료 토큰 거부 테스트
```

### 공통 헬퍼 (`shared/helpers.js`)

이벤트 상태 확인과 토큰 폴링을 모든 테스트 파일이 공유한다.

```javascript
// 이벤트가 ACTIVE가 될 때까지 2초 간격으로 폴링 (최대 maxSeconds)
export function waitForEventActive(eventId, maxSeconds = 120) { ... }

// 지정한 유저 목록 전원의 토큰이 발급될 때까지 폴링
export function waitForAllTokens(userIds, eventId, maxSeconds = 15) { ... }
```

> `02`, `03`, `05`는 setup()에서 `waitForEventActive()`를 호출한다.  
> 이벤트가 SCHEDULED 상태이면 최대 120초 자동 대기 후 테스트를 시작한다.

---

## 실행 순서

```bash
# 1. 이벤트 생명주기 경계 (자체 상태 초기화 포함, 먼저 실행)
k6 run k6/04_lifecycle_boundary.js

# 2. 클레임 동시 경합 (핵심 정확성 검증)
k6 run k6/02_claim_race.js

# 3. 전체 흐름 통합
k6 run k6/03_full_flow.js

# 4. 만료 토큰 거부
k6 run k6/05_expired_token.js

# 5. 대기열 입장 스트레스 (이벤트 ACTIVE 상태에서)
k6 run k6/01_queue_stress.js
```

---

## 01. 대기열 입장 스트레스 테스트

**목적:** 100명이 동시에 대기열에 입장할 때 서버 응답 시간과 에러율을 검증한다.

**검증 항목**

| 항목 | 기준 |
|------|------|
| 응답 시간 p95 | 500ms 이하 |
| HTTP 에러율 | 1% 미만 |
| ZADD NX 동작 | 같은 userId 중복 입장 시 rank 중복 없음 |

**부하 패턴**

```
0s ──────► 5s   50명까지 증가
5s ──────► 15s  100명 유지
15s ─────► 20s  0명으로 감소
```

**실행 결과 예시**

```
checks.........................: 100.00% ✓ 2000
http_req_duration..............: avg=32ms   p(95)=95ms
http_req_failed................: 0.00%   ✓ 0
http_reqs......................: 2000    95/s
```

---

## 02. 클레임 동시 경합 테스트 (핵심)

**목적:** 재고(20개)보다 많은 인원(30명)이 동시에 claim할 때 재고가 정확히 지켜지는지 검증한다.

**기대 결과**

```
총 재고: 텀블러 5 + 에코백 8 + 머그컵 3 + 키링 4 = 20개
테스트 인원: 30명

claim_success.....: 20   (재고 총합)
claim_sold_out....: 10   (30 - 20)
claim_error.......: 0    ← 반드시 0이어야 정상
```

**흐름**

```
setup()
  1. admin/reset  (이전 테스트 데이터 제거)
  2. waitForEventActive()  (이벤트 ACTIVE 대기)
  3. 30명 대기열 입장
  4. waitForAllTokens()  (전원 토큰 발급 대기, 최대 15초)

default function()  ← 30 VU 동시 실행
  각 VU가 자신의 토큰으로 /claim 요청

teardown()
  결과 집계 안내 출력
```

**Threshold**

```javascript
thresholds: {
    'claim_error': ['count==0'],  // 예상치 못한 오류 없음
}
```

---

## 03. 전체 흐름 통합 테스트

**목적:** 실제 유저처럼 20명이 각자 독립적으로 입장 → 폴링 → 수령까지 전체 여정을 완주한다.

**특징**

- `setup()`에서 `waitForEventActive()` 호출 → 이벤트 ACTIVE 확인 후 시작
- 각 VU가 독립적으로 전체 여정 수행 (setup에서 토큰 일괄 발급 없음)
- 상품은 VU 번호 기준으로 분산 선택 (`(__VU % 4) + 1`)

**흐름**

```
각 VU:
  1. POST /queue/enter     (대기열 입장)
  2. GET  /queue/status    (1초마다 폴링, 최대 30초)
  3. POST /claim           (토큰 발급 후 수령)
```

**Threshold**

```javascript
thresholds: {
    http_req_duration: ['p(95)<1000'],
}
```

---

## 04. 이벤트 생명주기 경계 테스트

**목적:** SCHEDULED → ACTIVE → ENDED 각 단계에서 대기열과 토큰 발급이 올바르게 동작하는지 검증한다.

**시나리오 (단일 VU, 순차 실행)**

```
사전 준비
  admin/reset          (수령 이력, 토큰, 대기열 초기화)
  admin/force-schedule (이벤트 SCHEDULED 상태로 리셋)

Phase 1 — SCHEDULED
  3명 대기열 입장 → rank 반환 확인
  3초 대기 → 토큰 미발급 확인  ← processQueue가 SCHEDULED 이벤트를 무시

Phase 2 — ACTIVE
  admin/force-activate
  waitForAllTokens() 최대 15초 대기
  3명 전원 토큰 발급 확인  ← processQueue가 즉시 처리

Phase 3 — ENDED
  admin/force-end
  신규 유저 입장 → rank 반환 확인
  5초 대기 → 토큰 미발급 확인  ← ENDED 이벤트는 activeEventCache에 없음
```

**Threshold**

```javascript
thresholds: {
    'lifecycle_errors': ['count==0'],
}
```

**이 테스트가 검증하는 것**

- `processQueue`가 `activeEventCache`의 ACTIVE 이벤트만 처리하는지
- `force-end` 후 `queue:waiting:{eventId}` 삭제로 새 입장자가 처리되지 않는지
- 상태 전환 후 캐시 즉시 반영 여부

---

## 05. 만료 토큰 거부 테스트

**목적:** Redis에서 토큰 키가 사라진 경우(TTL 만료 시뮬레이션) claim 요청이 401로 거부되는지 검증한다.

> 실제 TTL은 300초이므로 기다리지 않고, 관리자 API `DELETE /api/v1/admin/token`으로 Redis 키를 즉시 삭제해 만료를 재현한다.

**흐름**

```
setup()
  1. waitForEventActive()
  2. admin/reset
  3. admin/force-activate  (reset 후 이벤트 재활성화)
  4. 유저 2명 입장 (expiredUser, validUser)
  5. 두 유저 토큰 발급 대기

default function()
  Step 1. DELETE /admin/token  (expiredUser 토큰 만료 시뮬레이션)
  Step 2. expiredUser로 claim → 401 UNAUTHORIZED 검증
  Step 3. validUser로 claim   → 200 또는 409 검증 (비교군)
```

**Threshold**

```javascript
thresholds: {
    'token_errors': ['count==0'],
}
```

**이 테스트가 검증하는 것**

- `ClaimService`가 Redis 토큰 키 없으면 반드시 401 반환
- 토큰 만료 후 재사용 불가
- 유효한 토큰은 정상 처리됨 (비교군)

---

## k6 결과 읽는 법

```
✓ 상태코드 200
✗ 응답에 rank 포함
  ↳ 95% — 95 / 100

checks.........................: 97.50%  ✓ 195  ✗ 5
http_req_duration..............: avg=45ms   p(90)=80ms   p(95)=120ms
http_req_failed................: 0.00%   ✓ 0    ✗ 200
http_reqs......................: 200     9.5/s
vus............................: 100     min=0 max=100

claim_success..................: 20
claim_sold_out.................: 10
claim_error....................: 0
```

| 항목 | 의미 |
|------|------|
| `checks` | check() 통과 비율 |
| `http_req_duration p(95)` | 상위 95% 응답 시간 (느린 5% 제외한 기준값) |
| `http_req_failed` | HTTP 에러율 (4xx, 5xx) |
| `http_reqs` | 초당 처리 요청 수 (RPS) |
| 커스텀 카운터 | `claim_success`, `lifecycle_errors` 등 테스트별 정의 |

### p95가 뭔가?

100명이 요청했을 때 응답 시간을 빠른 순으로 줄 세운 다음, 95번째 사람의 응답 시간. **상위 5%의 느린 케이스를 제외한 기준값**이다. 평균보다 실제 체감에 가까운 지표다.

---

## 테스트 전 체크리스트

```
□ 백엔드 서버 실행 중?  curl http://localhost:8081/api/v1/events/1/status
□ MySQL + Redis 실행 중?  docker ps (또는 kubectl get pod -n fcfs)
□ 이벤트 데이터 존재?  DataInitializer가 서버 최초 기동 시 자동 생성
□ k6 설치됨?  k6 version
```

---

## DB 정합성 검증 SQL

테스트 후 MySQL에 직접 접속해 재고와 수령 이력이 일치하는지 확인한다.

```sql
-- 수령 총 건수 (재고 합계 이하여야 함)
SELECT COUNT(*) FROM claim;

-- 상품별 재고 차감 vs 수령 이력 정합 확인
SELECT
    p.name,
    p.total_stock   AS 초기재고,
    p.stock         AS 남은재고,
    p.total_stock - p.stock AS 차감된재고,
    COUNT(c.id)     AS 수령건수,
    CASE WHEN (p.total_stock - p.stock) = COUNT(c.id)
         THEN '✓ 정합'
         ELSE '✗ 불일치'
    END AS 검증결과
FROM product p
LEFT JOIN claim c ON p.id = c.product_id
GROUP BY p.id;

-- 중복 수령 확인 (결과가 비어있어야 정상)
SELECT user_id, COUNT(*) AS cnt
FROM claim
GROUP BY user_id
HAVING cnt > 1;

-- 토큰 상태 분포 확인
SELECT status, COUNT(*) AS cnt
FROM queue_token
GROUP BY status;
-- VALID: 아직 미사용, USED: 수령 완료, EXPIRED: 60초 배치가 만료 처리
```
