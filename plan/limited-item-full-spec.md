# FCFS-Claim 선착순 지급 시스템 — 전체 설계 문서

> 프리퀀시 방식의 한정 수량 아이템 선착순 지급 시스템  
> Spring Boot 3.x / Java 17 / Redis / MySQL + React Native / Flutter 앱

---

## 목차

**[Part 1 — 시스템 아키텍처]**
1. [시스템 개요](#1-시스템-개요)
2. [전체 아키텍처 구조](#2-전체-아키텍처-구조)
3. [기술 스택](#3-기술-스택)
4. [패키지 구조](#4-패키지-구조)
5. [도메인 설계](#5-도메인-설계)
6. [DB 스키마](#6-db-스키마)
7. [Redis 설계](#7-redis-설계)
8. [핵심 로직 — Race Condition 방지](#8-핵심-로직--race-condition-방지)
9. [API 설계](#9-api-설계)
10. [부하 테스트 모듈](#10-부하-테스트-모듈)
11. [예외 처리 전략](#11-예외-처리-전략)
12. [인프라 구성](#12-인프라-구성)

**[Part 2 — UI 디자인 스펙]**
13. [화면 구조 개요](#13-화면-구조-개요)
14. [컴포넌트 스펙 (dp 단위)](#14-컴포넌트-스펙-dp-단위)
15. [컬러 토큰](#15-컬러-토큰)
16. [타이포그래피 스케일](#16-타이포그래피-스케일)
17. [간격 시스템](#17-간격-시스템)
18. [애니메이션 스펙](#18-애니메이션-스펙)
19. [상태 시나리오](#19-상태-시나리오)

---

# Part 1 — 시스템 아키텍처

## 1. 시스템 개요

### 핵심 문제

동시에 다수의 요청이 들어올 때, **정확히 설정된 수량(N개)만 지급**되어야 한다.

```
500개 동시 요청 → 정확히 20개 성공 / 480개 실패
```

### 핵심 해결 전략

```
Redis DECR + Lua Script → 원자적(Atomic) 재고 차감
```

Redis의 단일 스레드 특성과 Lua Script의 원자성을 활용해 Race Condition을 근본적으로 차단한다.

---

## 2. 전체 아키텍처 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                         앱 클라이언트                            │
│              ┌──────────────┐  ┌──────────────────┐            │
│              │  정상 모드    │  │  부하테스트 모드   │            │
│              │  (단건 요청)  │  │  (N개 동시 발사)  │            │
│              └──────┬───────┘  └────────┬─────────┘            │
└─────────────────────┼───────────────────┼──────────────────────┘
                      │ HTTP              │ HTTP
                      ▼                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Spring Boot Application                     │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                     API Layer                            │  │
│  │   ItemClaimController      LoadTestController            │  │
│  │   POST /api/v1/claim       POST /api/v1/load-test/fire   │  │
│  └────────────────────────────┬─────────────────────────────┘  │
│                               │                                 │
│  ┌────────────────────────────▼─────────────────────────────┐  │
│  │                   Service Layer                           │  │
│  │                  ItemClaimService                         │  │
│  │   1. 중복 수령 체크 (Redis SET)                           │  │
│  │   2. 원자적 재고 차감 (Redis Lua Script)                  │  │
│  │   3. 수령 이력 저장 (MySQL)                               │  │
│  └──────────┬────────────────────────────┬───────────────────┘  │
│             │                            │                      │
│  ┌──────────▼──────────┐    ┌────────────▼──────────────────┐  │
│  │   Redis Layer        │    │        JPA Layer              │  │
│  │                      │    │                               │  │
│  │  item:stock:{id}     │    │  ClaimHistoryRepository       │  │
│  │  item:claimed:{id}   │    │  ItemEventRepository          │  │
│  └──────────────────────┘    └───────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                      │                    │
               ┌──────┘                    └──────┐
               ▼                                  ▼
         ┌──────────┐                       ┌──────────┐
         │  Redis   │                       │  MySQL   │
         │  :6379   │                       │  :3306   │
         └──────────┘                       └──────────┘
```

### 요청 처리 시퀀스

```
사용자 요청
    │
    ▼
[Controller] POST /api/v1/claim
    │
    ▼
[중복 수령 체크] ──── 이미 수령? ──→ 409 ALREADY_CLAIMED
    │ 미수령
    ▼
[Redis Lua Script 원자적 차감]
    │
    ├── 재고 > 0 → DECR 성공 ──→ [DB 저장] ──→ 200 SUCCESS
    │
    └── 재고 = 0 ──────────────────────────────→ 409 STOCK_EXHAUSTED
```

---

## 3. 기술 스택

| 계층 | 기술 | 선택 이유 |
|------|------|-----------|
| 언어 | Java 17 | Record, Sealed class 활용 |
| 프레임워크 | Spring Boot 3.x | 생산성, 생태계 |
| 재고 관리 | Redis 7.x | 원자적 DECR, 인메모리 고성능 |
| Redis 클라이언트 | Lettuce (Spring Data Redis) | Non-blocking |
| 분산락 (선택) | Redisson | 복잡한 락 시나리오 대응 |
| RDBMS | MySQL 8.x | 수령 이력 영속 저장 |
| ORM | Spring Data JPA | 타입 안전 쿼리 |
| 빌드 | Gradle 8.x | |
| 컨테이너 | Docker Compose | 로컬 개발 환경 |
| 모니터링 | Micrometer + Prometheus + Grafana | 메트릭 수집 시각화 |
| API 문서 | SpringDoc OpenAPI 3 | Swagger UI |

---

## 4. 패키지 구조

```
src/main/java/com/example/fcfsclaim/
│
├── FcfsClaimApplication.java
│
├── config/
│   ├── RedisConfig.java              # Redis 설정, Lua Script 등록
│   ├── JpaConfig.java
│   └── SwaggerConfig.java
│
├── domain/
│   ├── item/
│   │   ├── entity/
│   │   │   ├── ItemEvent.java        # 이벤트 (수량 정보)
│   │   │   └── ClaimHistory.java    # 수령 이력
│   │   ├── repository/
│   │   │   ├── ItemEventRepository.java
│   │   │   └── ClaimHistoryRepository.java
│   │   ├── service/
│   │   │   └── ItemClaimService.java
│   │   ├── controller/
│   │   │   └── ItemClaimController.java
│   │   └── dto/
│   │       ├── ClaimRequest.java
│   │       └── ClaimResponse.java
│   │
│   └── loadtest/
│       ├── controller/LoadTestController.java
│       ├── service/LoadTestService.java
│       └── dto/LoadTestResult.java
│
├── infrastructure/
│   └── redis/
│       ├── RedisStockRepository.java
│       └── RedisKeyConstants.java
│
└── common/
    ├── exception/
    │   ├── FcfsClaimException.java
    │   ├── ErrorCode.java
    │   └── GlobalExceptionHandler.java
    └── response/ApiResponse.java
```

---

## 5. 도메인 설계

### ItemEvent

```java
@Entity
@Table(name = "item_events")
public class ItemEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;           // "프리퀀시 2025"
    private int totalQuantity;     // 20
    private EventStatus status;    // READY / ACTIVE / CLOSED
    private LocalDateTime startAt;
    private LocalDateTime endAt;

    public enum EventStatus { READY, ACTIVE, CLOSED }
}
```

### ClaimHistory

```java
@Entity
@Table(name = "claim_history",
  uniqueConstraints = @UniqueConstraint(columnNames = {"event_id","user_id"}))
public class ClaimHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long eventId;
    private Long userId;
    private int sequenceNumber;    // 몇 번째 수령자 (1~20)
    private LocalDateTime claimedAt;
}
```

---

## 6. DB 스키마

```sql
CREATE TABLE item_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    total_quantity INT          NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'READY',
    start_at       DATETIME,
    end_at         DATETIME,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status (status)
);

CREATE TABLE claim_history (
    id              BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT   NOT NULL,
    user_id         BIGINT   NOT NULL,
    sequence_number INT      NOT NULL,
    claimed_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_user (event_id, user_id),
    INDEX idx_event_id (event_id),
    INDEX idx_user_id (user_id)
);
```

---

## 7. Redis 설계

### Key 구조

| Key | Type | 설명 |
|-----|------|------|
| `item:stock:{eventId}` | String | 남은 재고 수량 |
| `item:claimed:{eventId}` | Set | 수령 완료한 userId 집합 |

### 핵심 Lua Script

```lua
-- KEYS[1] = item:stock:{eventId}
-- KEYS[2] = item:claimed:{eventId}
-- ARGV[1] = userId

-- 1. 중복 수령 체크
local already = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if already == 1 then
    return -2   -- 이미 수령
end

-- 2. 재고 확인 및 원자적 차감
local stock = tonumber(redis.call('GET', KEYS[1]))
if not stock or stock <= 0 then
    return -1   -- 재고 소진
end

-- 3. 차감 + 수령자 등록 (원자적)
local remaining = redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return remaining  -- 0 이상이면 성공
```

**반환값:** `-2` 중복 | `-1` 재고 소진 | `0 이상` 성공

---

## 8. 핵심 로직 — Race Condition 방지

```java
@Service
@RequiredArgsConstructor
public class ItemClaimService {

    private final RedisStockRepository redisStockRepository;
    private final ClaimHistoryRepository claimHistoryRepository;

    @Transactional
    public ClaimResponse claim(Long eventId, Long userId) {
        // Redis 원자적 차감 (핵심)
        long result = redisStockRepository.tryClaimAtomic(eventId, userId);

        if (result == -2L) throw new FcfsClaimException(ErrorCode.ALREADY_CLAIMED);
        if (result == -1L) throw new FcfsClaimException(ErrorCode.STOCK_EXHAUSTED);

        // 성공 — sequence_number = 전체수량 - 남은재고
        int seq = getEvent(eventId).getTotalQuantity() - (int) result;
        claimHistoryRepository.save(ClaimHistory.of(eventId, userId, seq));

        return ClaimResponse.success(seq, result);
    }
}
```

---

## 9. API 설계

### 엔드포인트

| Method | URL | 설명 |
|--------|-----|------|
| `POST` | `/api/v1/events` | 이벤트 생성 |
| `POST` | `/api/v1/events/{id}/activate` | 이벤트 활성화 + 재고 초기화 |
| `POST` | `/api/v1/claim` | 아이템 수령 신청 |
| `GET`  | `/api/v1/events/{id}/stock` | 현재 재고 조회 |
| `POST` | `/api/v1/load-test/fire` | 부하 테스트 실행 |

### 응답 예시

**성공 (200)**
```json
{
  "success": true,
  "data": {
    "sequenceNumber": 7,
    "remainingStock": 13,
    "message": "7번째로 수령하셨습니다!"
  }
}
```

**재고 소진 (409)**
```json
{
  "success": false,
  "error": { "code": "STOCK_EXHAUSTED", "message": "수량이 모두 소진되었습니다." }
}
```

---

## 10. 부하 테스트 모듈

```java
public LoadTestResult fire(Long eventId, int totalReq, int threads) throws InterruptedException {
    itemClaimService.resetStockForTest(eventId, 20);

    CountDownLatch startLatch = new CountDownLatch(1);  // 동시 출발 신호
    CountDownLatch endLatch   = new CountDownLatch(totalReq);
    AtomicInteger success     = new AtomicInteger(0);
    AtomicInteger fail        = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < totalReq; i++) {
        final long userId = 100000L + i;
        executor.submit(() -> {
            try {
                startLatch.await();
                itemClaimService.claim(eventId, userId);
                success.incrementAndGet();
            } catch (FcfsClaimException e) {
                fail.incrementAndGet();
            } finally {
                endLatch.countDown();
            }
        });
    }

    startLatch.countDown();  // 모든 스레드 동시 출발
    endLatch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    // 검증: success == 20 이어야 PASS
    return LoadTestResult.builder()
        .successCount(success.get())
        .failCount(fail.get())
        .passed(success.get() == 20)
        .build();
}
```

---

## 11. 예외 처리 전략

```java
public enum ErrorCode {
    STOCK_EXHAUSTED("C001", "수량이 모두 소진되었습니다.", HttpStatus.CONFLICT),
    ALREADY_CLAIMED("C002", "이미 수령하셨습니다.",      HttpStatus.CONFLICT),
    EVENT_NOT_FOUND("E001", "이벤트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    EVENT_NOT_ACTIVE("E002", "진행 중인 이벤트가 아닙니다.", HttpStatus.BAD_REQUEST);
}
```

---

## 12. 인프라 구성

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: fcfs_claim
      MYSQL_ROOT_PASSWORD: password
    ports: ["3306:3306"]

  redis:
    image: redis:7.0-alpine
    ports: ["6379:6379"]
    command: redis-server --appendonly yes --maxmemory 256mb

  prometheus:
    image: prom/prometheus:latest
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana:latest
    ports: ["3000:3000"]
```

---

# Part 2 — UI 디자인 스펙

## 13. 화면 구조 개요

```
┌─────────────────────────┐  ← 320dp
│  Header            56dp │  Fixed top
├─────────────────────────┤
│  Banner            96dp │
├─────────────────────────┤
│  SectionHeader     38dp │
├─────────────────────────┤
│  ProductCard       88dp │  margin: 0 12dp
│  ProductCard       88dp │
│  ProductCard       88dp │
│  ProductCard       88dp │
├─────────────────────────┤
│  BottomActionBar   80dp │  Fixed bottom
└─────────────────────────┘
```

---

## 14. 컴포넌트 스펙 (dp 단위)

### Header

```
W: 320dp  H: 56dp
Padding: 14 16 12
Gap: 8dp  Direction: Row
Fill: #1E3932
Position: Fixed top / Z-index: 100

  ├── Logo
  │     W: 28dp  H: 28dp  Radius: 50%
  │     BG: #00A862
  │
  ├── Title
  │     Font: 13px 700  Color: #FFFFFF
  │     Sub:  10px 400  Color: #8EBD9E
  │
  └── FreqChip
        BG: #CBA258  Color: #1E3932
        Font: 10px 700  Padding: 3 8  Radius: 20dp
```

### Banner

```
W: 320dp  H: 96dp
Padding: 16dp  Direction: Column
Fill: linear-gradient(135°, #1E3932 → #2C5234)

  ├── Label:  9px 700  Letter-spacing: 2px  Color: #F5D78E
  ├── Title:  20px Serif  Color: #F2F0EB  Line-height: 1.2
  └── Desc:   10px 400  Color: #8EBD9E
```

### SectionHeader

```
W: 320dp  H: 38dp
Padding: 14 14 8  Direction: Row  justify: space-between
Fill: #F2F0EB

  ├── Title:  14px 700  Color: #1E1B16
  └── Count:  11px 400  Color: #9A9184
```

### ProductCard (공통)

```
W: 296dp  H: 88dp   (margin: 0 12dp)
BG: #FFFFFF  Radius: 16dp
Shadow: 0 2dp 8dp rgba(0,0,0,.06)
Direction: Row

  ├── ImageArea
  │     W: 88dp  H: 88dp
  │     BG: 상품별 파스텔 그라데이션
  │
  └── CardBody  Padding: 10 10 10 8
        ├── Name:  12px 700
        ├── Desc:  10px 400  Color: #9A9184
        ├── StockBar  ← 아래 스펙 참조
        └── StatusBadge ← 아래 스펙 참조
```

### ProductCard — 상태별 차이

| 상태 | Border | Shadow | Opacity | 특이사항 |
|------|--------|--------|---------|---------|
| Available | 없음 | 기본 | 1.0 | — |
| Selected | 2.5dp #00704A | 0 4dp 14dp green 15% | 1.0 | — |
| SoldOut | 없음 | 기본 | 0.7 | Overlay + Tag |
| LowStock | 없음 | 기본 | 1.0 | Bar orange |

**SoldOut Overlay:**
```
BG: rgba(255,255,255,.55)
backdrop-filter: blur(1px)

  Tag:
    BG: #D4000D  Color: white
    Font: 10px 700  Padding: 4 12  Radius: 20dp
    Transform: rotate(-8°)
    Shadow: 0 3dp 10dp rgba(212,0,13,.3)
```

### StockBar

```
W: 160dp  H: 3dp
Track BG: #E8E4DC  Radius: 2dp

Fill 색상 규칙:
  stock > 30%  →  #00A862  (green)
  stock ≤ 30%  →  #E8701A  (orange)
  stock = 0    →  width 0% / color #D4000D

Transition: width 1200ms cubic-bezier(0.4, 0, 0.2, 1)
```

### StatusBadge

```
H: 20dp  W: auto  Padding: 3 7  Radius: 20dp
Font: 9px 700  Letter-spacing: 0.2px

  신청 가능  →  BG #E8F5EE  Text #00704A
  잔여 N개   →  BG #FEF0E4  Text #E8701A
  품절       →  BG #FEEAEB  Text #D4000D
```

### BottomActionBar

```
W: 320dp  H: 80dp
Padding: 10 14 16  Direction: Column  Gap: 7dp
BG: #FFFFFF
Position: Fixed bottom
Shadow: 0 -4dp 24dp rgba(0,0,0,.08)

  ├── HintText:  11px 400  Color: #9A9184
  └── ClaimButton
        W: 292dp  H: 44dp
        Padding: 12dp  Radius: 10dp
        Font: 13px 700  Transition: all 0.2s

        Active:   BG #00704A  Color #FFFFFF
                  :active → scale(0.97)
        Disabled: BG #E8E4DC  Color #9A9184
```

---

## 15. 컬러 토큰

| 토큰 | 값 | 용도 |
|------|-----|------|
| `brand-green` | #00704A | Primary CTA, Selected border |
| `brand-green-dark` | #1E3932 | Header BG, Banner start |
| `brand-green-mid` | #00A862 | Logo BG, Bar fill ok |
| `gold` | #CBA258 | FreqChip BG |
| `gold-light` | #F5D78E | Banner label |
| `cream` | #F2F0EB | Page BG |
| `cream-2` | #E8E4DC | Bar track, Disabled btn BG |
| `text-primary` | #1E1B16 | 기본 텍스트 |
| `text-muted` | #9A9184 | 보조 텍스트, 힌트 |
| `orange` | #E8701A | Low stock 경고 |
| `red` | #D4000D | Sold out / 품절 |
| `green-bg` | #E8F5EE | 신청가능 뱃지 BG |
| `orange-bg` | #FEF0E4 | 잔여 뱃지 BG |
| `red-bg` | #FEEAEB | 품절 뱃지 BG |

---

## 16. 타이포그래피 스케일

| 레벨 | Size | Weight | Font | 용도 |
|------|------|--------|------|------|
| display | 20px | 400 | DM Serif Display | Banner title |
| title-lg | 14px | 700 | Noto Sans KR | Section title |
| title-md | 13px | 700 | Noto Sans KR | Header, CTA button |
| title-sm | 12px | 700 | Noto Sans KR | Card name |
| body | 11px | 400 | Noto Sans KR | Hint text |
| caption | 10px | 400/700 | Noto Sans KR | Sub, desc, chip |
| micro | 9px | 700 | Noto Sans KR | Badge, Banner label |

---

## 17. 간격 시스템 (8dp 그리드)

| 토큰 | 값 | 사용처 |
|------|-----|--------|
| space-1 | 4dp | 내부 미세 여백 |
| space-2 | 8dp | 컴포넌트 내부 gap |
| space-3 | 12dp | 카드 좌우 마진 |
| space-4 | 14dp | 섹션 헤더 패딩 |
| space-5 | 16dp | 배너 패딩 |
| space-6 | 20dp | 바텀바 하단 패딩 |

---

## 18. 애니메이션 스펙

| 요소 | 속성 | Duration | Easing |
|------|------|----------|--------|
| StockBar fill | width | 1200ms | cubic-bezier(.4,0,.2,1) |
| ProductCard tap | transform scale | 150ms | ease |
| ClaimButton tap | scale(0.97) | 200ms | ease |
| SoldOut overlay | opacity | 300ms | ease |
| Modal sheet | translateY | 350ms | cubic-bezier(.32,.72,0,1) |
| Toast in | opacity + translateY | 300ms | cubic-bezier(.175,.885,.32,1.275) |
| Toast out | opacity | 300ms at 2.5s | ease |

---

## 19. 상태 시나리오

### 시나리오 A — 정상 선택 → 신청 성공

```
1. 진입: 4개 상품 표시 (1개 품절, 3개 신청 가능)
2. 상품 카드 탭 → Selected 상태 (green border + shadow)
3. BottomBar: 선택 상품명 표시 + CTA 활성화
4. 신청 버튼 탭 → API POST /claim
5. 성공 모달: "N번째로 수령하셨습니다!"
```

### 시나리오 B — 둘러보는 중 재고 소진

```
1. 사용자가 카드를 선택 중 (Selected 상태)
2. 서버에서 재고 소진 이벤트 수신 (WebSocket or polling)
3. Toast 알림: "⚡ [상품명] 재고가 소진되었습니다"
4. 해당 카드 → SoldOut 상태 전환 (애니메이션)
5. 선택 해제 + BottomBar 비활성화
```

### 시나리오 C — 신청 시 품절 응답

```
1. 신청 버튼 탭
2. API 응답: 409 STOCK_EXHAUSTED
3. 실패 모달: "아쉽게도 품절됐어요"
4. 카드 상태 SoldOut으로 업데이트
5. "다른 상품 보기" 버튼 → 모달 닫기
```

### 시나리오 D — 부하 테스트 모드

```
1. 개발자 패널에서 "500개 동시 요청 발사" 선택
2. 백엔드: CountDownLatch로 500개 스레드 동시 출발
3. 정확히 20개 성공 / 480개 실패 처리
4. 검증: successCount == 20 → PASS
```

### 검증 체크리스트

| 항목 | 기대값 |
|------|--------|
| 성공 응답 수 | 정확히 20 |
| 실패 응답 수 | 480 |
| DB claim_history 행 | 20건 |
| Redis 최종 재고 | 0 |
| 중복 수령 여부 | 0건 |
| sequence_number 범위 | 1 ~ 20 (중복 없음) |

---

*문서 버전: 1.0 | 기준 해상도: 360dp Android / 375pt iOS*
*Spring Boot 3.x / Java 17 / Redis 7.x / MySQL 8.x*

---

# Part 3 — 대기열(Waiting Room) 설계

## 대기열 아키텍처

```
앱 진입 (지금 입장하기 버튼)
    │
    ▼
POST /api/v1/queue/enter
    │
    ▼
Redis Sorted Set에 등록
ZADD queue:{eventId} {timestamp} {userId}
    │
    ▼
내 순번 반환 (ZRANK)
    │
    ▼
클라이언트: SSE or Polling으로 순번 변화 구독
    │
    ├── 아직 대기 중  → "N번째 대기 중" 화면 유지
    │
    └── 순번 도달     → 입장 토큰 발급
                           │
                           ▼
                      POST /api/v1/claim (토큰 포함)
                           │
                           └── 기존 재고 차감 로직 동일
```

## Redis 대기열 Key 구조

| Key | Type | 설명 |
|-----|------|------|
| `queue:{eventId}` | Sorted Set | score=진입시각, member=userId |
| `queue:token:{token}` | String | 입장 토큰 (TTL 5분) |
| `queue:processing:{eventId}` | String | 현재 처리 중인 순번 커서 |

## 핵심 커맨드

```
진입:    ZADD queue:1 {now} {userId}        → 대기열 등록
순번:    ZRANK queue:1 {userId}             → 내 앞 대기 수
처리:    ZPOPMIN queue:1 {n}               → 앞 N명 꺼내서 입장 처리
토큰:    SET queue:token:{uuid} {userId} EX 300   → 5분짜리 입장 토큰
검증:    GET queue:token:{token}            → 토큰 유효성 확인
```

## 스케줄러 (입장 처리)

```java
@Scheduled(fixedDelay = 1000)  // 1초마다 실행
public void processQueue() {
    // 백엔드가 초당 처리 가능한 RPS만큼만 입장 허용
    int allowPerSecond = 10;

    List<String> next = redisTemplate.opsForZSet()
        .popMin("queue:" + eventId, allowPerSecond);

    for (String userId : next) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
            .set("queue:token:" + token, userId, 300, TimeUnit.SECONDS);
        // SSE or Push로 해당 userId에게 토큰 전달
        notifyUser(userId, token);
    }
}
```

## UI 화면 흐름

```
[진입 화면]           [대기 화면]           [입장 완료]         [상품 선택]
현재 대기 인원  →    N번째 대기 중   →    차례가 됐습니다!  →  기존 UI
남은 수량             진행률 바             5분 카운트다운
예상 대기 시간        앞 대기 N명           입장 순번
지금 입장하기 버튼    처리 속도
```

## 대기열 진입 통제 기준

| 기준 | 값 | 설명 |
|------|-----|------|
| 초당 입장 허용 | 백엔드 처리 RPS | Spring Boot + Redis 처리 가능 수치로 결정 |
| 입장 토큰 TTL | 300초 (5분) | 토큰 발급 후 5분 내 신청 안 하면 취소 |
| 대기 최대 인원 | 설정값 | 초과 시 "마감 임박" 안내 |
| 수량 소진 시 | 즉시 대기열 종료 | 남은 대기자 전원 안내 후 종료 |
