# 아키텍처

현재 구현된 시스템의 컴포넌트 구조와 핵심 설계 결정을 설명한다.

---

## 전체 구성

```
[클라이언트]
     │
     ▼
  [Nginx]          리버스 프록시, 로드밸런서, Rate Limit
     │
     ├──▶ [App 1]  ─┐
     └──▶ [App 2]  ─┼──▶ [MySQL]   영구 저장 (이벤트, 토큰, 수령 이력)
                    └──▶ [Redis]   대기열 ZSET, 토큰, Pub/Sub, ShedLock
```

**로컬 개발:** Docker Compose (`--scale app=2`로 다중 인스턴스)  
**배포 환경:** Kubernetes, HPA로 1~5 파드 자동 스케일

---

## 컴포넌트별 역할

### Nginx

```nginx
# Rate Limit
limit_req_zone $binary_remote_addr zone=queue_zone:10m rate=5r/s;   # 대기열 진입
limit_req_zone $binary_remote_addr zone=api_zone:10m rate=30r/s;    # 일반 API

# 로드밸런싱 (round-robin)
upstream backend { server app1:8081; server app2:8081; }
```

- `/api/v1/queue/enter`에 가장 엄격한 Rate Limit 적용 (초당 5회)
- 부하를 여러 앱 인스턴스에 분산

### Spring Boot 앱

각 인스턴스는 상태를 공유하지 않는다. 공유 상태는 모두 Redis에 있다. 단, SSE 연결(`SseEmitterStore`)은 인스턴스 로컬이다.

### Redis

| 용도 | 방식 |
|------|------|
| 대기열 | Sorted Set (ZADD NX) |
| 토큰 저장/검증 | String (TTL 300초) |
| 멀티 인스턴스 SSE | Pub/Sub |
| 분산 락 | ShedLock (String 키) |

### MySQL

| 용도 | 이유 |
|------|------|
| 이벤트, 상품, 수령 이력 | 영구 저장, 트랜잭션 보장 |
| queue_token | Redis TTL 만료 후에도 이력 보존 |
| claim UNIQUE 제약 | 중복 수령 최종 방어선 |

---

## 대기열 처리 흐름

```
enter() 호출
  └─▶ Redis ZADD queue:waiting:{eventId} NX
        score = System.currentTimeMillis()  (먼저 들어온 순서 보장)
        member = userId
        NX 옵션 = 이미 있으면 추가 안 함 (중복 입장 방지)

processQueue() — 1초마다 ShedLock으로 단 1개 인스턴스만 실행
  └─▶ activeEventCache.getAll() → ACTIVE 이벤트 ID 목록 (메모리 읽기, DB 조회 없음)
  └─▶ Redis ZPOPMIN queue:waiting:{eventId} COUNT 10 (앞에서 10명 추출)
  └─▶ UUID 토큰 생성
  └─▶ Redis SET token:{eventId}:{token} {userId} EX 300
  └─▶ Redis SET user:token:{eventId}:{userId} {token} EX 300
  └─▶ DB QueueToken 저장
  └─▶ Redis Pub/Sub "queue:ready" 발행 → 모든 인스턴스가 수신
```

---

## 멀티 인스턴스 SSE 문제와 해결

### 문제

SSE 연결은 특정 인스턴스에만 맺어진다. 스케줄러가 app1에서 실행되면 app2에 연결된 유저에게 토큰을 전달할 방법이 없다.

```
스케줄러 → app1에서 실행
  userId=A 토큰 발급 → app1 SseEmitterStore → A는 app1에 연결됨 ✓ 전송
  userId=B 토큰 발급 → app1 SseEmitterStore → B는 app2에 연결됨 ✗ 못 찾음
```

### 해결: Redis Pub/Sub

```
스케줄러 (어느 인스턴스든)
  └─▶ Redis Pub/Sub "queue:ready" 에 {eventId, userId, token} 발행

모든 인스턴스 (QueueReadySubscriber)
  └─▶ 구독 수신
  └─▶ 자신의 SseEmitterStore 에서 userId 조회
  └─▶ 있으면 → SSE 전송 ✓
      없으면 → 조용히 무시 (다른 인스턴스가 처리)
```

이벤트 종료 알림도 동일한 방식. "queue:ended" 채널로 eventId를 발행하면 모든 인스턴스가 해당 이벤트의 SSE 연결에 종료 이벤트를 보낸다.

---

## 분산 락 (ShedLock)

### 왜 필요한가

여러 파드가 동시에 `processQueue()`를 실행하면 같은 유저에게 토큰이 중복 발급된다.  
Redis `ZPOPMIN`은 원자적이지만, 토큰 발급/저장 로직 전체가 원자적이지 않다.

### 구현 방식

**고정 이름 락 — `@SchedulerLock` 어노테이션**

```java
@Scheduled(fixedDelay = 1000)
@SchedulerLock(name = "processQueue", lockAtMostFor = "PT2S", lockAtLeastFor = "PT1S")
public void processQueue() { ... }
```

**동적 이름 락 — 프로그래매틱 API**

`@SchedulerLock`은 상수 이름만 지원한다. `activateEvent-1`, `endEvent-2`처럼 이벤트 ID를 포함한 동적 이름이 필요할 때는 `LockProvider`를 직접 사용한다.

```java
Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
    Instant.now(),
    "activateEvent-" + eventId,   // 이벤트별 독립 락
    Duration.ofSeconds(30),        // lockAtMostFor: 비정상 종료 시 자동 해제
    Duration.ofSeconds(5)          // lockAtLeastFor: 최소 락 유지 시간
));
if (lock.isEmpty()) return;  // 다른 인스턴스가 처리 중
try { doWork(); } finally { lock.get().unlock(); }
```

---

## 이벤트 생명주기 관리

### ActiveEventCache — DB 조회 제거

`processQueue()`는 1초마다 실행된다. 매번 DB에서 ACTIVE 이벤트를 조회하면 초당 DB 히트가 발생한다. 인메모리 캐시로 대체한다.

```java
private final AtomicReference<Set<Long>> activeEventIds = new AtomicReference<>(Set.of());

@Scheduled(fixedDelay = 30_000)   // 30초마다 DB에서 동기화 (No ShedLock — 각 인스턴스가 자체 캐시)
public void refresh() { ... }

public void add(Long eventId)    { ... }  // 이벤트 활성화 시 즉시 반영
public void remove(Long eventId) { ... }  // 이벤트 종료 시 즉시 반영
```

`AtomicReference.updateAndGet()`(CAS)으로 원자적 교체한다. `volatile`은 가시성만 보장하고 "읽기→수정→쓰기"의 원자성은 보장하지 않아 두 스레드가 동시에 `add()`하면 한쪽이 유실될 수 있다.

`add()`/`remove()`는 `@TransactionalEventListener(AFTER_COMMIT)`에서 호출된다. 트랜잭션 안에서 호출하면 DB 커밋 전에 캐시가 갱신되어, 그 사이에 `refresh()`가 실행되면 구 DB 상태로 캐시를 덮어쓰는 레이스가 생기기 때문이다.

### TaskScheduler — 정각에 이벤트 전환

60초 배치 폴링 대신 정확한 시각에 실행한다.

```java
taskScheduler.schedule(
    () -> lifecycleService.activateEvent(eventId),
    event.getStartAt().atZone(ZoneId.systemDefault()).toInstant()
);
```

TaskScheduler는 재시작 시 초기화된다. `EventRecoveryService`(ApplicationRunner)가 서버 기동 시 DB를 조회해 스케줄을 재등록한다.

### EventRecoveryService — 서버 재시작 복구

```
서버 재시작
  └─▶ EventRecoveryService.run() (ApplicationRunner — 스프링 컨텍스트 완전히 뜬 후)
        ① end_at 지난 이벤트 → 즉시 endEvent()
        ② start_at 됐지만 SCHEDULED인 이벤트 → 즉시 activateEvent()
        ③ ACTIVE 이벤트 → activeEventCache.add() + 종료 TaskScheduler 재등록
        ④ SCHEDULED 이벤트 → 활성화 + 종료 TaskScheduler 등록
```

`@PostConstruct`가 아닌 `ApplicationRunner`를 쓰는 이유: JPA, 트랜잭션, TaskScheduler가 모두 준비된 후에 실행해야 하기 때문이다.

---

## 재고 차감 동시성

```
ClaimService.claim() — @Transactional
  1. Redis 토큰 키 존재 확인 → 없으면 401
  2. Claim 저장 (DB UNIQUE 제약 — 중복 시 DataIntegrityViolationException → 409)
  3. Product 재고 차감 (조건부 UPDATE)
       UPDATE product SET stock = stock - 1
       WHERE id = ? AND stock > 0
       → affected rows = 0 이면 재고 소진 → 예외 → 트랜잭션 롤백 (Claim도 롤백)
  4. Redis 토큰 키 삭제
  5. QueueToken.markUsed()
```

`stock > 0` 조건을 DB가 원자적으로 처리하므로 재고 초과 차감이 발생하지 않는다. 트랜잭션 롤백으로 Claim 저장도 함께 취소된다.

---

## HPA (Horizontal Pod Autoscaler)

```yaml
minReplicas: 1
maxReplicas: 5
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 50   # 평균 CPU 50% 초과 시 스케일 업

behavior:
  scaleUp:
    stabilizationWindowSeconds: 15   # 15초 연속 초과 시 스케일 업
  scaleDown:
    stabilizationWindowSeconds: 60   # 60초 연속 여유 시 스케일 다운
```

스케일 업 시 새 파드의 `EventRecoveryService`가 ACTIVE 이벤트를 `activeEventCache`에 즉시 등록해 대기열 처리에 바로 참여한다.

---

## 토큰 만료 동기화 (TokenExpiryService)

Redis TTL 300초가 만료되면 토큰은 실제로 무효화되지만, DB `queue_token.status`는 VALID로 남는다. 60초마다 배치로 동기화한다.

```java
@Scheduled(fixedDelay = 60_000)
@SchedulerLock(name = "expireTokens", lockAtMostFor = "PT55S", lockAtLeastFor = "PT30S")
@Transactional
public void expireOverdueTokens() {
    int count = queueTokenRepository.expireOverdue(VALID, EXPIRED, LocalDateTime.now());
    // UPDATE queue_token SET status = 'EXPIRED'
    // WHERE status = 'VALID' AND expires_at < now()
}
```
