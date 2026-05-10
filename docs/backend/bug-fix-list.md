# 버그 수정 리스트

> 코드 리뷰를 통해 발견된 버그 및 개선 사항. 심각도 순 정렬.

---

## 목차

1. [BUG-01 — ActiveEventCache 경쟁 조건](#bug-01--activeeventcache-경쟁-조건)
2. [BUG-02 — claim 후 user:token 키 미삭제](#bug-02--claim-후-usertoken-키-미삭제)
3. [BUG-03 — processQueueForEvent 트랜잭션 누락](#bug-03--processqueueforevent-트랜잭션-누락)
4. [BUG-04 — SseEmitter 재연결 시 이전 연결 미정리](#bug-04--sseemitter-재연결-시-이전-연결-미정리)
5. [BUG-05 — SseEmitterStore.getByEventId() O(n) 전체 스캔](#bug-05--sseemitterstoregetbyeventid-on-전체-스캔)
6. [BUG-06 — EventLifecycleService enum 문자열 비교](#bug-06--eventlifecycleservice-enum-문자열-비교)
7. [BUG-07 — processQueueForEvent 개별 save 반복](#bug-07--processqueueforevent-개별-save-반복)

---

## BUG-01 — ActiveEventCache 경쟁 조건

**파일**: `domain/event/service/ActiveEventCache.java`
**심각도**: 중 (재현 어렵지만 실제 버그)
**종류**: Thread Safety

### 문제

`add()` / `remove()`는 "읽기 → 수정 → 쓰기" 세 단계로 이루어진다.
`volatile`은 참조 교체의 **가시성**만 보장하고, 이 세 단계의 **원자성**은 보장하지 않는다.

```java
private volatile Set<Long> activeEventIds = Set.of();

public void add(Long eventId) {
    Set<Long> updated = new java.util.HashSet<>(activeEventIds); // (A) 읽기
    updated.add(eventId);
    this.activeEventIds = Set.copyOf(updated);                   // (B) 쓰기
}

@Scheduled(fixedDelay = 30_000)
public void refresh() {
    Set<Long> updated = eventRepository.findByStatus(EventStatus.ACTIVE)...;
    this.activeEventIds = updated;                               // (C) 덮어쓰기
}
```

**레이스 시나리오**:
```
스케줄러 스레드:  (A) activeEventIds = {1} 읽음
refresh 스레드:   (C) activeEventIds = {1, 2} 로 갱신
스케줄러 스레드:  (B) activeEventIds = {1, 3} 으로 덮어씀 → eventId 2 유실
```

`refresh()`는 ShedLock 없이 30초마다 모든 인스턴스에서 돌기 때문에,
`doActivate()` / `doEnd()`와 타이밍이 겹치면 캐시에서 이벤트 ID가 유실된다.

유실된 이벤트 ID는 `processQueue()`가 처리하지 않아서 대기열이 멈추는 증상으로 나타난다.

### 수정 방법

`AtomicReference` + `updateAndGet()`으로 원자적 교체:

```java
import java.util.concurrent.atomic.AtomicReference;

private final AtomicReference<Set<Long>> activeEventIds =
        new AtomicReference<>(Set.of());

public void add(Long eventId) {
    activeEventIds.updateAndGet(current -> {
        Set<Long> updated = new java.util.HashSet<>(current);
        updated.add(eventId);
        return Set.copyOf(updated);
    });
}

public void remove(Long eventId) {
    activeEventIds.updateAndGet(current -> {
        Set<Long> updated = new java.util.HashSet<>(current);
        updated.remove(eventId);
        return Set.copyOf(updated);
    });
}

@Scheduled(fixedDelay = 30_000)
public void refresh() {
    Set<Long> updated = eventRepository.findByStatus(EventStatus.ACTIVE)
            .stream()
            .map(Event::getId)
            .collect(Collectors.toUnmodifiableSet());
    activeEventIds.set(updated);
}

public Set<Long> getAll() {
    return activeEventIds.get();
}
```

`updateAndGet()`은 CAS(Compare-And-Swap)를 내부적으로 사용해 원자성을 보장한다.
경쟁이 발생하면 성공할 때까지 재시도하므로 lock 없이 안전하다.

---

## BUG-02 — claim 후 user:token 키 미삭제

**파일**: `domain/claim/service/ClaimService.java`
**심각도**: 낮 (UX 혼란, 보안 위험 없음)
**종류**: 상태 불일치

### 문제

`claim()` 성공 후 Redis에서 삭제되는 키와 남아있는 키:

```java
// 삭제됨 ✓
redis.delete("token:" + eventId + ":" + token);

// 삭제 안 됨 ✗
// "user:token:{eventId}:{userId}" 는 TTL 300초 동안 그대로 남음
```

`getStatus()`는 `user:token` 키를 먼저 확인한다:

```java
public StatusResponse getStatus(Long userId, Long eventId) {
    String token = redis.opsForValue().get(userTokenKey(eventId, userId)); // ← 아직 있음
    if (token != null) {
        return StatusResponse.ready(token); // ← claim 완료했는데도 "준비" 반환
    }
    ...
}
```

**결과**: 수령 완료 후 프론트엔드가 `getStatus()`를 호출하면 ready 상태가 반환된다.
만료 토큰으로 claim 재시도하면 401이 나지만, 유저 입장에선 "이미 수령했는데 왜 오류?"로 보인다.

### 수정 방법

```java
// 4. 토큰 소진
redis.delete(tokenRedisKey);
redis.delete(userTokenKey(request.eventId(), request.userId())); // ← 추가
```

`userTokenKey()`는 `QueueService`에 private으로 있으므로, `ClaimService`에서 동일한 패턴으로 직접 생성하거나 유틸 메서드로 분리:

```java
private String userTokenKey(Long eventId, Long userId) {
    return "user:token:" + eventId + ":" + userId;
}
```

---

## BUG-03 — processQueueForEvent 트랜잭션 누락

**파일**: `domain/queue/service/QueueService.java`
**심각도**: 중 (부분 실패 시 데이터 불일치)
**종류**: 트랜잭션 무결성

### 문제

```java
private void processQueueForEvent(Long eventId) {
    Set<ZSetOperations.TypedTuple<String>> users =
            redis.opsForZSet().popMin(waitingKey(eventId), PROCESS_PER_SECOND);

    for (ZSetOperations.TypedTuple<String> entry : users) {
        Long userId = Long.valueOf(entry.getValue());
        String token = UUID.randomUUID().toString();

        redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);  // (1)
        redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL); // (2)
        queueTokenRepository.save(QueueToken.of(eventId, userId, token));          // (3)
        publish(new QueueReadyMessage(eventId, userId, token));                     // (4)
    }
}
```

`@Transactional`이 없어서 각 `save()`가 독립 트랜잭션으로 실행된다.

**부분 실패 시나리오** (10명을 처리하다 5번째에서 DB 오류):
- 1~4번째: Redis 토큰 설정 + DB 저장 완료 (커밋됨)
- 5번째: Redis 토큰은 설정됐지만 DB 저장 실패
- 5번째 유저: Redis에는 토큰이 있어서 claim 가능, DB에는 QueueToken 없음 → 불일치

또한 `popMin()`으로 Redis에서 꺼낸 뒤 DB 저장이 실패하면,
해당 유저는 대기열에서도 사라지고 토큰도 없는 상태가 된다.

### 수정 방법

```java
@Transactional
private void processQueueForEvent(Long eventId) {
    ...
}
```

단, `@Transactional`을 추가해도 Redis 작업은 DB 트랜잭션 밖이다.
완벽한 원자성은 불가능하지만, DB 저장 실패 시 전체 롤백은 가능하다.

더 나은 접근은 DB 저장을 먼저 모아서 `saveAll()`로 한 번에:

```java
@Transactional
private void processQueueForEvent(Long eventId) {
    Set<ZSetOperations.TypedTuple<String>> users =
            redis.opsForZSet().popMin(waitingKey(eventId), PROCESS_PER_SECOND);

    if (users == null || users.isEmpty()) return;

    List<QueueToken> tokens = new ArrayList<>();
    List<QueueReadyMessage> messages = new ArrayList<>();

    for (ZSetOperations.TypedTuple<String> entry : users) {
        Long userId = Long.valueOf(entry.getValue());
        String token = UUID.randomUUID().toString();

        redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);
        redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL);
        tokens.add(QueueToken.of(eventId, userId, token));
        messages.add(new QueueReadyMessage(eventId, userId, token));
    }

    queueTokenRepository.saveAll(tokens); // DB 저장 실패 시 트랜잭션 전체 롤백
    messages.forEach(this::publish);
}
```

---

## BUG-04 — SseEmitter 재연결 시 이전 연결 미정리

**파일**: `domain/queue/service/SseEmitterStore.java`
**심각도**: 낮 (메모리 누수 가능성)
**종류**: 리소스 관리

### 문제

```java
public void put(Long eventId, Long userId, SseEmitter emitter) {
    emitters.put(key(eventId, userId), emitter); // 이전 emitter를 complete() 없이 덮어씀
}
```

같은 유저가 SSE를 재연결하면 (네트워크 끊김, 앱 재시작):
- 기존 emitter 참조가 Map에서 제거된다
- 기존 emitter의 `complete()` / `completeWithError()`가 호출되지 않는다
- Tomcat은 해당 연결을 아직 열린 것으로 인식할 수 있다
- GC가 회수하기 전까지 연결이 유지되는 상태가 된다

### 수정 방법

```java
public void put(Long eventId, Long userId, SseEmitter emitter) {
    SseEmitter old = emitters.put(key(eventId, userId), emitter);
    if (old != null) {
        try {
            old.complete(); // 이전 연결 명시적으로 종료
        } catch (Exception ignored) {
            // 이미 완료된 emitter는 예외 발생 가능, 무시
        }
    }
}
```

---

## BUG-05 — SseEmitterStore.getByEventId() O(n) 전체 스캔

**파일**: `domain/queue/service/SseEmitterStore.java`
**심각도**: 낮 (현재 규모에서 체감 없음, 이벤트/유저 수 증가 시 문제)
**종류**: 성능

### 문제

```java
// 이벤트 종료 알림 시 전체 emitter를 순회
public Map<Long, SseEmitter> getByEventId(Long eventId) {
    String prefix = eventId + ":";
    return emitters.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix)) // ← 전체 스캔
            .collect(...);
}
```

동시 접속 10,000명이 5개 이벤트에 분산되면,
이벤트 종료 알림 한 번에 10,000개 엔트리를 전부 순회해서 2,000개를 찾는다.

### 수정 방법

이중 Map으로 eventId → userId → emitter 직접 접근:

```java
// key 구조 변경
private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, SseEmitter>> emitters =
        new ConcurrentHashMap<>();

public void put(Long eventId, Long userId, SseEmitter emitter) {
    emitters.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>()).put(userId, emitter);
}

public SseEmitter get(Long eventId, Long userId) {
    ConcurrentHashMap<Long, SseEmitter> eventMap = emitters.get(eventId);
    return eventMap == null ? null : eventMap.get(userId);
}

public void remove(Long eventId, Long userId) {
    ConcurrentHashMap<Long, SseEmitter> eventMap = emitters.get(eventId);
    if (eventMap != null) {
        eventMap.remove(userId);
        if (eventMap.isEmpty()) emitters.remove(eventId);
    }
}

// O(n) → O(1) — eventId로 바로 접근
public Map<Long, SseEmitter> getByEventId(Long eventId) {
    ConcurrentHashMap<Long, SseEmitter> eventMap = emitters.get(eventId);
    return eventMap == null ? Map.of() : Map.copyOf(eventMap);
}
```

---

## BUG-06 — EventLifecycleService enum 문자열 비교

**파일**: `domain/event/service/EventLifecycleService.java`
**심각도**: 낮 (현재는 문제없으나 리팩터링 시 잡기 어려운 버그 유발)
**종류**: 코드 품질

### 문제

```java
@Transactional
protected void doActivate(Long eventId) {
    Event event = eventRepository.findById(eventId).orElse(null);
    if (event == null || !event.getStatus().name().equals("SCHEDULED")) return; // ← 문자열 비교
    ...
}
```

`EventStatus.SCHEDULED`를 문자열 `"SCHEDULED"`로 비교하고 있다.

`EventStatus` enum 값의 이름이 바뀌거나, 오타가 생겨도 컴파일 타임에 에러가 나지 않는다.
예: `EventStatus.SCHEDULED` → `EventStatus.PENDING`으로 변경 시 이 코드는 항상 조기 리턴한다.

### 수정 방법

```java
if (event == null || event.getStatus() != EventStatus.SCHEDULED) return;
```

---

## BUG-07 — processQueueForEvent 개별 save 반복

**파일**: `domain/queue/service/QueueService.java`
**심각도**: 낮 (현재 규모에서 체감 없음)
**종류**: 성능
**참고**: BUG-03 수정 시 함께 해결됨

### 문제

```java
for (ZSetOperations.TypedTuple<String> entry : users) {
    queueTokenRepository.save(QueueToken.of(...)); // ← 최대 10번 별도 DB 왕복
}
```

`PROCESS_PER_SECOND = 10`이면 초당 DB INSERT 10번이 개별로 발생한다.
Redis도 `set()`을 10번 개별 호출한다.

### 수정 방법

BUG-03의 `saveAll()` 수정으로 DB는 해결된다.
Redis도 파이프라인으로 묶으면 네트워크 왕복을 줄일 수 있다:

```java
redis.executePipelined((RedisCallback<Object>) connection -> {
    for (TokenEntry t : tokenEntries) {
        connection.stringCommands().set(
            userTokenKey(t.eventId(), t.userId()).getBytes(),
            t.token().getBytes(),
            Expiration.from(TOKEN_TTL),
            SetOption.UPSERT
        );
    }
    return null;
});
```

단, 현재 초당 10명 처리 규모에서는 Redis 파이프라인 효과가 미미하다.
`PROCESS_PER_SECOND`를 대폭 늘릴 때 검토한다.

---

## 수정 우선순위 요약

| # | 파일 | 심각도 | 수정 난이도 | 먼저 할 것 |
|---|------|--------|------------|-----------|
| BUG-01 | ActiveEventCache | 중 | 낮음 | ✅ 추천 |
| BUG-02 | ClaimService | 낮 | 매우 낮음 | ✅ 추천 |
| BUG-03 | QueueService | 중 | 낮음 | ✅ 추천 |
| BUG-04 | SseEmitterStore | 낮 | 낮음 | 추천 |
| BUG-05 | SseEmitterStore | 낮 | 중간 | 규모 커질 때 |
| BUG-06 | EventLifecycleService | 낮 | 매우 낮음 | ✅ 추천 (1분) |
| BUG-07 | QueueService | 낮 | 낮음 | BUG-03과 함께 |
