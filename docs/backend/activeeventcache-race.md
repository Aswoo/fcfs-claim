# ActiveEventCache 레이스 컨디션 — 원인과 해결

## 배경

`processQueue`는 1초마다 실행되며 현재 활성화된 이벤트의 대기열을 처리한다. 매번 DB를 조회하면 부하가 크기 때문에 인메모리 `ActiveEventCache`에 ACTIVE 이벤트 ID를 캐시해둔다.

```
processQueue (1초마다)
  └─ activeEventCache.getAll()  →  ACTIVE 이벤트 목록
       └─ 각 이벤트 대기열에서 ZPOPMIN 10명 처리

ActiveEventCache
  ├─ add(id) / remove(id)  — 이벤트 상태 변경 시 즉시 반영
  └─ refresh()             — 30초마다 DB에서 전체 재동기화
```

---

## 문제: 트랜잭션 커밋 전에 캐시를 업데이트했다

### 원래 코드

```java
@Transactional
protected void doActivate(Long eventId) {
    Event event = eventRepository.findById(eventId).orElse(null);
    if (event == null || event.getStatus() != EventStatus.SCHEDULED) return;

    event.activate();
    activeEventCache.add(eventId);  // ← 트랜잭션 안에서 호출
}
```

`activeEventCache.add(eventId)`는 DB 커밋 전에 실행된다. 이 순간 캐시에는 이벤트 ID가 들어가 있지만, DB에는 아직 `ACTIVE` 상태가 커밋되지 않았다.

### 레이스 타임라인

```
스레드 A (doActivate)              스레드 B (refresh, 30초마다)
─────────────────────────────────  ─────────────────────────────────
트랜잭션 시작
event.activate()
  └─ DB write (미커밋 상태)
activeEventCache.add(1L)
  └─ cache = {1L}
                                   refresh() 실행
                                     └─ DB 조회: ACTIVE 이벤트 없음
                                          (커밋 전이라 보이지 않음)
                                     └─ cache = {}  ← 덮어씀!
트랜잭션 커밋
  └─ DB: event.status = ACTIVE
```

이후 `processQueue`가 실행되면 `cache.getAll() = {}`이므로 처리할 이벤트가 없다고 판단하고 다음 `refresh()`(최대 30초 후)까지 대기열을 처리하지 않는다.

### 실제 증상

이벤트가 정시에 `ACTIVE`로 바뀌었는데 대기열이 처리되지 않고 최대 30초 지연된다.

---

## 해결: `@TransactionalEventListener(AFTER_COMMIT)`

DB 커밋이 완료된 이후에 캐시를 업데이트하도록 순서를 바꿨다.

### 구조

```
doActivate() @Transactional
  ├─ event.activate()
  ├─ publishEvent(EventActivatedEvent)  ← 이벤트 발행만 (즉시 실행 안 함)
  └─ 커밋

커밋 완료 후
  └─ EventLifecycleListener.onActivated() @AFTER_COMMIT
       └─ activeEventCache.add(eventId)  ← 이제 DB 상태와 일치
```

### 변경된 코드

```java
// EventLifecycleService
@Transactional
protected void doActivate(Long eventId) {
    Event event = eventRepository.findById(eventId).orElse(null);
    if (event == null || event.getStatus() != EventStatus.SCHEDULED) return;

    event.activate();
    eventPublisher.publishEvent(new EventActivatedEvent(eventId));  // 커밋 후 처리 예약
}

// EventLifecycleListener
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onActivated(EventActivatedEvent event) {
    activeEventCache.add(event.eventId());  // DB 커밋 완료 후 실행
}
```

### 수정 후 타임라인

```
스레드 A (doActivate)              스레드 B (refresh, 30초마다)
─────────────────────────────────  ─────────────────────────────────
트랜잭션 시작
event.activate()
  └─ DB write (미커밋)
publishEvent(EventActivatedEvent)
  └─ 이벤트 대기 중 (아직 실행 안 됨)
트랜잭션 커밋
  └─ DB: event.status = ACTIVE
@AFTER_COMMIT 실행
  └─ activeEventCache.add(1L)
       └─ cache = {1L}
                                   refresh() 실행
                                     └─ DB 조회: ACTIVE 이벤트 1개
                                     └─ cache = {1L}  ← 일치
```

커밋 후에 캐시가 갱신되므로 `refresh()`가 어느 타이밍에 실행되더라도 DB 상태와 캐시가 일치한다.

---

## 같은 이유로 doEnd()도 함께 수정했다

`doEnd()`도 트랜잭션 안에서 Redis 작업을 했다. DB가 아직 `ENDED`로 커밋되지 않은 상태에서 `queue:ended` 채널로 SSE 종료 메시지를 보내면, 클라이언트는 이벤트가 종료됐다고 인식하지만 DB를 직접 조회하면 아직 `ACTIVE`로 보이는 불일치가 생긴다.

```java
// 수정 전 doEnd()
event.end();
activeEventCache.remove(eventId);                        // 커밋 전
redis.delete("queue:waiting:" + eventId);                // 커밋 전
redis.convertAndSend(QUEUE_ENDED_CHANNEL, ...);          // 커밋 전 — 클라이언트에 SSE 전송

// 수정 후: 모두 @AFTER_COMMIT 리스너로 이동
event.end();
eventPublisher.publishEvent(new EventEndedEvent(eventId)); // 커밋 후 처리 예약
```

---

## 한계

`@AFTER_COMMIT`은 트랜잭션이 성공적으로 커밋된 경우에만 실행된다. 만약 커밋 후 JVM이 비정상 종료되면 캐시 업데이트가 누락될 수 있다. 이 경우 `refresh()`(30초 주기)가 DB 상태를 읽어 캐시를 복구하므로 최대 30초의 윈도우 안에서 자가 복구된다.

완전한 보장이 필요하다면 캐시 없이 매번 DB를 조회하거나, 커밋과 캐시 업데이트를 하나의 원자적 단위로 묶는 Outbox 패턴을 도입해야 한다. 현재 구조에서는 30초 복구 윈도우를 허용 가능한 트레이드오프로 판단했다.
