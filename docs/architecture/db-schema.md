# DB 스키마

## ERD

```
event (1) ──< product (N)
event (1) ──< queue_token (N)
event (1) ──< claim (N)
product (1) ──< claim (N)
```

---

## 테이블 정의

### `event`
선착순 지급 행사 단위. 시작/종료 시각과 상태 머신을 가진다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| name | VARCHAR(100) | NOT NULL | 이벤트명 |
| start_at | DATETIME | NOT NULL | 이벤트 시작 시각 |
| end_at | DATETIME | NOT NULL | 이벤트 종료 시각 |
| status | VARCHAR(10) | NOT NULL | SCHEDULED / ACTIVE / ENDED |
| created_at | DATETIME | NOT NULL | 생성 시각 |

**상태 전이:** `SCHEDULED → ACTIVE → ENDED`
- SCHEDULED: 생성됨, start_at 전
- ACTIVE: 진행 중, 대기열 처리
- ENDED: 종료, 대기열 정리됨

---

### `product`
이벤트에 속한 개별 상품. 상품별로 재고를 독립 관리한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| event_id | BIGINT | NOT NULL, FK → event.id | 소속 이벤트 |
| name | VARCHAR(100) | NOT NULL | 상품명 |
| description | VARCHAR(200) | NOT NULL | 설명 |
| stock | INT | NOT NULL | 현재 재고 (차감되는 값) |
| total_stock | INT | NOT NULL | 초기 재고 (불변, 소진율 계산용) |
| created_at | DATETIME | NOT NULL | 생성 시각 |

**설계 결정:**
- `stock`과 `total_stock` 분리: 재고 소진율(`stock / total_stock`) 표시 시 매번 이력 역산 없이 바로 계산 가능
- UI 속성(색상, 이미지)은 DB 저장 없이 프론트엔드 상수로 관리. DB는 비즈니스 데이터만

---

### `queue_token`
대기열을 통과한 유저에게 발급되는 입장 토큰. Redis TTL과 병행하여 영구 이력을 남긴다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| event_id | BIGINT | NOT NULL | 소속 이벤트 |
| user_id | BIGINT | NOT NULL | 유저 식별자 |
| token | VARCHAR(36) | NOT NULL, UNIQUE | UUID 토큰 |
| issued_at | DATETIME | NOT NULL | 발급 시각 |
| expires_at | DATETIME | NOT NULL | 만료 시각 (issued_at + 300초) |
| status | VARCHAR(10) | NOT NULL | VALID / USED / EXPIRED |

**UNIQUE 제약:** `(event_id, user_id)` — 한 유저는 한 이벤트에서 토큰을 하나만 발급받을 수 있다.

**Redis와 DB의 역할 분리:**
- Redis `token:{eventId}:{token}` (TTL 300초): 실시간 토큰 유효성 검증 (claim 요청마다 조회)
- DB `queue_token`: 영구 이력, 감사, 만료 배치 대상

**토큰 상태 동기화:** Redis TTL 만료 후 DB status는 `VALID`로 남는다. `TokenExpiryService`가 60초마다 `expires_at < now`인 VALID 토큰을 EXPIRED로 일괄 업데이트한다.

---

### `claim`
실제 상품 수령 완료 기록. 이 테이블에 row가 생기면 수령이 확정된 것이다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| event_id | BIGINT | NOT NULL | 소속 이벤트 |
| user_id | BIGINT | NOT NULL | 유저 식별자 |
| product_id | BIGINT | NOT NULL, FK → product.id | 수령한 상품 |
| token | VARCHAR(36) | NOT NULL | 사용된 토큰 (감사 추적용) |
| claimed_at | DATETIME | NOT NULL | 수령 시각 |

**UNIQUE 제약:** `(event_id, user_id)` — 한 유저는 한 이벤트에서 한 번만 수령. 중복 수령의 최종 방어선.

---

## 설계 결정 기록

### `claim.event_id` — 의도적 역정규화

`claim.product_id → product.event_id`이므로 `claim.event_id`는 3NF 위반이다. 그러나 "이벤트별 수령 건수" 조회는 운영 모니터링에서 매우 자주 발생하므로, `product` JOIN 없이 `claim(event_id)` 인덱스만으로 처리하기 위해 역정규화를 선택했다.

```sql
-- 역정규화 후: JOIN 없음
SELECT event_id, COUNT(*) FROM claim GROUP BY event_id;
```

### `claim.token` — audit 목적 중복

`queue_token` 테이블에 이미 token이 있지만, `claim`에도 저장한다. 목적이 다르다.
- `queue_token.token`: 토큰 생애 주기 관리 (VALID → USED → EXPIRED)
- `claim.token`: "어떤 토큰으로 수령했는지" 감사 추적. claim만 조회해도 토큰을 역추적할 수 있어야 함

### `@ManyToOne` 미사용

모든 FK는 `Long` 타입으로 직접 저장하며 JPA 연관관계 매핑을 쓰지 않는다. 부하 테스트 환경에서 대량 INSERT 시 JPA가 유발하는 암묵적 쿼리(지연 로딩 초기화 등)를 방지하고, Repository 레벨 쿼리를 명확하게 제어하기 위해서다.

---

## Redis 키 구조

| 키 패턴 | 타입 | TTL | 용도 |
|---------|------|-----|------|
| `queue:waiting:{eventId}` | ZSET | 없음 | 대기열 (score=timestamp, member=userId) |
| `token:{eventId}:{token}` | String | 300s | 토큰 유효성 검증 (value=userId) |
| `user:token:{eventId}:{userId}` | String | 300s | userId → token 역조회 |
| `shedlock:{lockName}` | String | lockAtMostFor | ShedLock 분산 락 |

---

## 현재 구조의 한계 (실무 대비)

| 항목 | 현재 | 실무 |
|------|------|------|
| User 테이블 | 없음 (ID만 사용) | user 테이블 + FK 제약 필요 |
| 재고 변동 이력 | 없음 | `inventory_log` 테이블로 추적 |
| 상품 이미지 | DB 없음, 프론트 상수 | S3 URL을 product 테이블에 저장 |
| 이벤트 관리자 | 없음 | admin 권한 분리, 이벤트 CRUD API |
