# 이벤트 생명주기

## 상태 전이

이벤트는 세 가지 상태를 순서대로 거친다. 역방향 전이는 없다.

```
SCHEDULED ──────▶ ACTIVE ──────▶ ENDED
  (시작 전)         (진행 중)        (종료)
```

| 상태 | 의미 | 대기열 처리 |
|---|---|---|
| SCHEDULED | start_at 이전 | 불가 (입장 차단) |
| ACTIVE | start_at ~ end_at | 가능 |
| ENDED | end_at 이후 | 불가 |

---

## 이벤트 생성과 TaskScheduler 등록

### 현재 구조 (DataInitializer)

서버 최초 시작 시 `CommandLineRunner`가 이벤트를 자동 생성한다. 이벤트 테이블이 비어있을 때만 실행된다.

```java
// DataInitializer.java
LocalDateTime start = LocalDateTime.now().plusMinutes(1);
LocalDateTime end   = start.plusHours(24);
Event event = eventRepository.save(Event.of("스타벅스 MD 한정 증정", start, end));

// DB 저장 직후 TaskScheduler에 등록
recoveryService.scheduleActivation(event);  // start_at에 activateEvent() 실행 예약
recoveryService.scheduleEnd(event);         // end_at에 endEvent() 실행 예약
```

### 실무 구조 (어드민 API)

실제 서비스에서는 관리자가 어드민 페이지에서 이벤트를 등록하고, API 호출 시점에 TaskScheduler에 등록한다.

```
POST /admin/events
  body: { name, startAt, endAt, products[] }

EventAdminService.createEvent()
  ├─ eventRepository.save(Event.of(...))      →  DB: SCHEDULED
  ├─ recoveryService.scheduleActivation(event) →  TaskScheduler: start_at에 활성화
  └─ recoveryService.scheduleEnd(event)        →  TaskScheduler: end_at에 종료
```

두 구조 모두 **"DB 저장 + TaskScheduler 등록"을 한 묶음으로 처리**한다는 점은 같다.

---

## 활성화 흐름 (start_at 도달)

```
TaskScheduler (start_at 정각)
  └─ lifecycleService.activateEvent(id)
       ├─ ShedLock "activateEvent-{id}" 획득 시도
       │   └─ 다중 파드 중 1개만 통과, 나머지 스킵
       └─ doActivate() @Transactional
            ├─ event.activate()           →  DB: ACTIVE
            └─ publishEvent(EventActivatedEvent)
                    └─ @AFTER_COMMIT
                         └─ activeEventCache.add(id)
                              →  processQueue가 이 이벤트 대기열 처리 시작
```

---

## 종료 흐름 (end_at 도달)

```
TaskScheduler (end_at 정각)
  └─ lifecycleService.endEvent(id)
       ├─ ShedLock "endEvent-{id}" 획득 시도
       └─ doEnd() @Transactional
            ├─ event.end()                →  DB: ENDED
            └─ publishEvent(EventEndedEvent)
                    └─ @AFTER_COMMIT  EventLifecycleListener.onEnded()
                         ├─ activeEventCache.remove(id)        →  processQueue 처리 중단
                         ├─ redis.delete("queue:waiting:{id}") →  남은 대기열 정리
                         └─ redis.convertAndSend("queue:ended", id)
                                │
                                │  Redis Pub/Sub 브로드캐스트 (모든 파드)
                                │
                         ┌──────┴──────┐
                        파드 1         파드 2
                   QueueEndedSubscriber.onMessage()
                         │
                   lifecycleService.notifyEventEnded(id)
                         │
                   emitterStore.getByEventId(id)
                   → 이 파드에 연결된 해당 이벤트 emitter 전체
                         │
                   emitter.send("ended")   ←  클라이언트에 종료 알림 전송
                   emitter.complete()      ←  SSE 연결 정상 종료
                         │
                   onCompletion 콜백 발동  ←  SseController에서 등록한 콜백
                   emitterStore.remove(id, userId)  ←  스토어에서 자동 제거
```

### onCompletion 콜백이 자동으로 정리하는 이유

```java
// SseController — 연결 시점에 콜백 등록
emitter.onCompletion(() -> emitterStore.remove(eventId, userId));
emitter.onTimeout(()   -> emitterStore.remove(eventId, userId));
emitter.onError(e      -> emitterStore.remove(eventId, userId));
```

`complete()`를 호출하면 Spring이 `onCompletion` 콜백을 발동시킨다. `notifyEventEnded()` 안에서 `emitterStore.remove()`를 따로 호출할 필요가 없다. 타임아웃·에러 상황도 같은 콜백으로 처리되어 스토어에 죽은 emitter가 남지 않는다.

### Pub/Sub 메시지가 유실된 경우 안전망

`queue:ended` 메시지를 못 받은 유저는 SSE 연결을 계속 들고 있게 된다. 이때 두 가지 안전망이 작동한다.

| 안전망 | 동작 |
|---|---|
| SSE 타임아웃 (300초) | 5분 후 `onTimeout` 콜백 → emitterStore 자동 제거 |
| 프론트엔드 폴링 | `GET /events/{id}/status` 주기 조회 → ENDED 확인 후 화면 전환 |

메시지 유실이 치명적이지 않은 이유는 최악의 경우 5분 후 타임아웃으로 자가 복구되기 때문이다.

---

## 서버 재시작 복구 (EventRecoveryService)

`TaskScheduler`는 JVM 메모리에 예약을 저장하기 때문에 서버가 재시작되면 등록된 모든 예약이 사라진다. `EventRecoveryService`는 서버가 뜰 때마다 DB를 읽어 필요한 예약을 다시 등록한다.

```
서버 시작 → EventRecoveryService.run()
  now = 현재 시각

  ① findOverdue()        end_at < now, 아직 ENDED 아닌 이벤트
                         → 즉시 endEvent()

  ② findActivatable()    SCHEDULED + start_at < now + end_at > now
                         → 즉시 activateEvent() + scheduleEnd() 재등록

  ③ findByStatus(ACTIVE) 이미 ACTIVE인 이벤트
                         → activeEventCache 복원 + scheduleEnd() 재등록

  ④ findByStatus(SCHEDULED) + start_at > now
                         → scheduleActivation() + scheduleEnd() 재등록
```

### 재시작 타임라인 예시

```
14:00  activateEvent 예약됨
14:05  서버 다운  →  TaskScheduler 메모리 소멸
14:10  서버 재시작

EventRecoveryService (now = 14:10)
  ①  end_at(14:30) > 14:10  →  해당 없음
  ②  SCHEDULED + start_at(14:00) < 14:10  →  해당 없음 (이미 ACTIVE)
  ③  ACTIVE 이벤트 발견
       └─ activeEventCache.add(id)    캐시 복원
       └─ scheduleEnd(14:30)          종료 예약 재등록
  ④  해당 없음
```

---

## 전체 타임라인 요약

```
어드민이 이벤트 등록
  └─ DB: SCHEDULED + TaskScheduler 예약 (activation, end)

start_at 도달
  └─ activateEvent()  →  DB: ACTIVE + 캐시 등록
       └─ processQueue(1초마다) 대기열 처리 시작

end_at 도달
  └─ endEvent()  →  DB: ENDED + 캐시 제거 + Redis 정리 + SSE 종료 알림
```

---

## 컴포넌트 역할 요약

| 컴포넌트 | 역할 |
|---|---|
| `TaskScheduler` | 정각에 활성화·종료 람다 실행 (JVM 메모리 → 재시작 시 소멸) |
| `EventRecoveryService` | 재시작 후 DB 기준으로 TaskScheduler 예약 재등록 |
| `ShedLock` | 다중 파드에서 activateEvent·endEvent 중복 실행 방지 |
| `ActiveEventCache` | processQueue가 매초 DB 조회하지 않도록 ACTIVE 이벤트 ID 캐시 |
| `EventLifecycleListener` | @AFTER_COMMIT 이후 캐시·Redis 후처리 (커밋 전 레이스 방지) |
