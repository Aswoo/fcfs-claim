# Redis Pub/Sub & 만료 토큰 배치 구현 가이드

> 멀티 인스턴스(K8s HPA 스케일업) 환경에서 SSE 알림이 누락되는 문제와,
> Redis TTL 만료 후 DB 상태가 VALID로 남는 문제를 해결합니다.

---

## 문제 1: 멀티 인스턴스에서 SSE 알림 누락

### 이 버그가 실제로 터지는 조건

**k6 테스트에서는 이 버그가 발생하지 않습니다.**

k6(`03_full_flow.js`)는 SSE가 아닌 **폴링** 방식으로 토큰 발급을 기다립니다.

```javascript
// k6가 사용하는 방식: 1초마다 상태를 직접 물어봄 (폴링)
while (Date.now() < deadline) {
    sleep(1);
    const statusRes = http.get(`/api/v1/queue/status?userId=...`);
    if (data?.isReady && data?.token) { ... }
}
```

폴링은 상태 비저장(stateless)이라 어느 인스턴스가 응답해도 Redis에서 동일한 값을 읽어 정상 동작합니다.

**이 버그가 터지는 실제 조건:**
- React Native 앱에서 SSE(`/queue/subscribe`)로 연결한 실제 유저
- + HPA로 파드가 2개 이상 뜬 상태
- + SSE 연결된 인스턴스와 스케줄러가 실행 중인 인스턴스가 다른 경우

즉, 실제 서비스에서 이벤트 시작 순간 수천 명이 SSE로 연결되어 있고 HPA가 인스턴스를 늘리는 상황에서 발생합니다. 현재 k6 테스트 환경에서는 재현되지 않지만, 실제 서비스에선 반드시 문제가 됩니다.

### 기존 구조의 문제점

```
[변경 전 흐름]

유저 A ──SSE 연결──→ app1 (SseEmitterStore에 저장)
유저 B ──SSE 연결──→ app2 (SseEmitterStore에 저장)

스케줄러(ShedLock) ──→ app1에서 실행됨
    └─→ userId=A 토큰 발급 ─→ app1의 SseEmitterStore에서 찾음 ✅ 전송됨
    └─→ userId=B 토큰 발급 ─→ app1의 SseEmitterStore에서 찾음 ❌ 없음!
                                (B는 app2에 연결되어 있으니까)
```

**핵심 원인:** `SseEmitterStore`는 각 인스턴스의 **메모리(힙)** 안에만 있습니다.
인스턴스가 2개 이상 뜨면 서로의 저장소를 볼 수 없습니다.

### 해결책: Redis Pub/Sub

```
[변경 후 흐름]

유저 A ──SSE 연결──→ app1 (SseEmitterStore에 저장)
유저 B ──SSE 연결──→ app2 (SseEmitterStore에 저장)

스케줄러(ShedLock) ──→ app1에서 실행됨
    └─→ userId=A, B 토큰 발급
    └─→ Redis "queue:ready" 채널에 메시지 발행(publish)
            ↓
    ┌─── Redis Pub/Sub 브로드캐스트 ───┐
    ↓                                  ↓
  app1 수신 → A의 SSE 있음 → 전송 ✅  app2 수신 → B의 SSE 있음 → 전송 ✅
```

**핵심 원리:** Redis는 발행된 메시지를 채널을 구독 중인 **모든 인스턴스에 동시 전달**합니다.
각 인스턴스는 자기 메모리에 연결된 유저의 SSE만 찾으면 됩니다.

---

## 구현 코드

### 1. QueueReadyMessage.java — 메시지 형식 정의

```java
// src/main/java/com/example/fcfsclaim/domain/queue/service/QueueReadyMessage.java

package com.example.fcfsclaim.domain.queue.service;

// Java 16+ record: 불변 데이터 클래스. getter, equals, hashCode, toString 자동 생성.
// Redis 채널을 통해 주고받을 메시지의 구조를 정의합니다.
public record QueueReadyMessage(
        Long eventId,   // 어떤 이벤트인지
        Long userId,    // 어떤 유저인지
        String token    // 발급된 토큰값
) {}
```

**왜 record를 쓰나요?**
Pub/Sub 메시지는 한 번 만들면 바뀌지 않는 데이터입니다.
`record`는 이런 불변 데이터 구조에 최적화되어 있고, 코드가 짧아집니다.

---

### 2. RedisConfig.java — Pub/Sub 인프라 설정

```java
// src/main/java/com/example/fcfsclaim/common/config/RedisConfig.java

@Configuration
public class RedisConfig {

    // 채널 이름을 상수로 관리. 발행자/구독자 모두 이 값을 참조합니다.
    // "queue:ready"라는 이름의 Redis 채널을 사용합니다.
    public static final String QUEUE_READY_CHANNEL = "queue:ready";

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        // ... (기존과 동일)
    }

    // Redis Pub/Sub 리스너 컨테이너 설정
    // 이 컨테이너가 Redis와 연결을 유지하면서 메시지가 오면 subscriber에게 전달합니다.
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory,
            QueueReadySubscriber subscriber) {    // Spring이 자동으로 주입해줌

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);

        // "queue:ready" 채널을 구독하고, 메시지가 오면 subscriber에게 전달하도록 등록
        container.addMessageListener(subscriber, new ChannelTopic(QUEUE_READY_CHANNEL));

        return container;
        // 이 Bean이 등록되면 앱 시작 시 Redis와 별도 연결을 맺고 구독을 시작합니다.
    }
}
```

**RedisMessageListenerContainer가 하는 일:**
- 앱 시작 시 Redis에 `SUBSCRIBE queue:ready` 명령 전송
- 이 채널로 메시지가 오면 등록된 `MessageListener`(= `QueueReadySubscriber`)를 호출
- 내부적으로 별도 스레드로 동작하여 메시지를 계속 기다림

---

### 3. QueueReadySubscriber.java — 메시지 수신 & SSE 전송

```java
// src/main/java/com/example/fcfsclaim/domain/queue/service/QueueReadySubscriber.java

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueReadySubscriber implements MessageListener {
    // MessageListener: Spring Data Redis가 제공하는 인터페이스
    // onMessage()를 구현하면 메시지가 왔을 때 자동으로 호출됩니다.

    private final SseEmitterStore emitterStore;  // 이 인스턴스의 SSE 연결 저장소
    private final ObjectMapper objectMapper;      // JSON ↔ 객체 변환기

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // message.getBody(): Redis에서 받은 원시 바이트 → JSON 문자열로 변환
        // 예: {"eventId":1,"userId":123,"token":"abc-def-..."}
        try {
            QueueReadyMessage msg = objectMapper.readValue(
                    message.getBody(),      // Redis에서 받은 바이트 배열
                    QueueReadyMessage.class // 변환할 클래스 타입
            );
            pushReady(msg.eventId(), msg.userId(), msg.token());
        } catch (Exception e) {
            log.warn("Pub/Sub 메시지 처리 실패: {}", e.getMessage());
        }
    }

    private void pushReady(Long eventId, Long userId, String token) {
        // 이 인스턴스(app1 or app2)의 메모리에서 해당 유저의 SSE 연결을 찾습니다.
        SseEmitter emitter = emitterStore.get(eventId, userId);

        if (emitter == null) {
            // 이 인스턴스에는 해당 유저의 SSE 연결이 없음 → 무시
            // 다른 인스턴스가 처리할 것입니다.
            return;
        }

        try {
            // SSE 이벤트 전송
            // name("ready"): 프론트엔드에서 addEventListener("ready", ...)로 받음
            // data(...): token 값을 JSON으로 전송
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("token", token)));
            emitter.complete(); // 연결 정상 종료 (일회성 알림이므로 바로 닫음)
        } catch (IOException e) {
            // 연결이 이미 끊어진 경우 (앱 종료, 네트워크 오류 등)
            log.warn("SSE 전송 실패 userId={}", userId);
            emitterStore.remove(eventId, userId); // 메모리에서 제거
        }
    }
}
```

**각 인스턴스가 받은 메시지를 왜 모두 처리해도 되나요?**

- app1, app2 모두 같은 메시지를 받지만, `emitterStore.get()`에서 null이 반환되면 아무것도 하지 않습니다.
- 실제로 해당 유저의 SSE 연결을 갖고 있는 인스턴스 하나만 `emitter.send()`를 실행합니다.
- **중복 전송이 발생하지 않습니다.**

---

### 4. QueueService.java — Pub/Sub publish로 교체

```java
// 변경 전 (직접 SSE 전송)
private void pushReady(Long eventId, Long userId, String token) {
    SseEmitter emitter = emitterStore.get(eventId, userId);  // 자기 메모리만 봄
    if (emitter == null) return;                              // 다른 인스턴스면 누락!
    // ...
}

// 변경 후 (Redis Pub/Sub으로 발행)
private void publish(QueueReadyMessage msg) {
    try {
        // ObjectMapper로 msg를 JSON 문자열로 변환 후 Redis 채널에 발행
        // 예: {"eventId":1,"userId":123,"token":"abc-def-..."}
        redis.convertAndSend(
                RedisConfig.QUEUE_READY_CHANNEL,        // 채널 이름
                objectMapper.writeValueAsString(msg)    // JSON 직렬화
        );
        // 이 시점에 Redis가 모든 구독자(app1, app2, ...)에게 메시지를 전달합니다.
    } catch (JsonProcessingException e) {
        log.warn("Pub/Sub 발행 실패 userId={}", msg.userId());
    }
}
```

**변경 포인트 요약:**

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| SSE 전송 위치 | `QueueService.pushReady()` | `QueueReadySubscriber.pushReady()` |
| 전달 방식 | 메모리에서 직접 | Redis 채널 경유 |
| 멀티 인스턴스 | ❌ 같은 인스턴스만 | ✅ 모든 인스턴스에 전달 |
| `SseEmitterStore` 의존 | `QueueService`가 직접 | `QueueReadySubscriber`만 |

---

## 문제 2: 만료된 토큰의 DB 상태 불일치

### 기존 구조의 문제점

```
토큰 발급 시점:
  Redis: token:1:abc-def  →  유효 (TTL 300초)
  DB:    queue_token 레코드  →  status = VALID

300초 후:
  Redis: 자동으로 키 삭제됨 (TTL 만료)
  DB:    status = VALID 그대로 남아있음 ← 불일치!
```

**왜 문제인가요?**
- 감사(audit) 목적으로 DB를 조회하면 만료된 토큰도 VALID로 보임
- 나중에 "이 유저는 왜 입장 못 했나?" 분석 시 잘못된 데이터로 오판 가능

### 해결책: 만료 토큰 배치

1분마다 `expiresAt < 현재시각 AND status = VALID`인 레코드를 `EXPIRED`로 일괄 변경합니다.

---

### 5. QueueTokenRepository.java — 만료 처리 쿼리 추가

```java
// src/main/java/com/example/fcfsclaim/domain/queue/repository/QueueTokenRepository.java

// @Modifying: SELECT가 아닌 UPDATE/DELETE 쿼리임을 JPA에 알림
//             이 어노테이션 없으면 "Expected SELECT query" 예외 발생
// @Query: Spring Data JPA의 메서드 이름 규칙으로 표현하기 어려운 복잡한 쿼리를 직접 작성
@Modifying
@Query("UPDATE QueueToken q " +
       "SET q.status = :expired " +           // VALID → EXPIRED로 변경
       "WHERE q.status = :valid " +            // VALID인 것만 대상
       "AND q.expiresAt < :now")               // 현재 시각보다 만료 시각이 이미 지난 것
int expireOverdue(
        @Param("valid")   TokenStatus valid,    // TokenStatus.VALID
        @Param("expired") TokenStatus expired,  // TokenStatus.EXPIRED
        @Param("now")     LocalDateTime now     // 현재 시각
);
// 반환값: 실제로 업데이트된 행 수 (로그 출력에 사용)
```

**왜 String literal 대신 enum 파라미터를 쓰나요?**

```java
// 안 좋은 방법 (타입 안정성 없음)
@Query("... SET q.status = 'EXPIRED' ...")

// 좋은 방법 (컴파일 타임에 오타 체크)
@Query("... SET q.status = :expired ...")
int expireOverdue(@Param("expired") TokenStatus expired, ...)
```

enum을 파라미터로 넘기면 오타로 인한 런타임 에러를 컴파일 타임에 잡을 수 있습니다.

---

### 6. TokenExpiryService.java — 배치 스케줄러

```java
// src/main/java/com/example/fcfsclaim/domain/queue/service/TokenExpiryService.java

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExpiryService {

    private final QueueTokenRepository queueTokenRepository;

    @Transactional           // DB 트랜잭션 안에서 실행 (실패 시 롤백)
    @Scheduled(fixedDelay = 60_000)  // 이전 실행 완료 후 60초마다 실행
    @SchedulerLock(
            name = "expireTokens",       // ShedLock이 이 이름으로 락을 잡음
            lockAtMostFor = "PT55S",     // 최대 55초 동안 락 유지 (비정상 종료 대비)
            lockAtLeastFor = "PT30S"     // 최소 30초는 락 유지 (완료해도 30초 유지)
    )
    public void expireOverdueTokens() {
        int count = queueTokenRepository.expireOverdue(
                TokenStatus.VALID,
                TokenStatus.EXPIRED,
                LocalDateTime.now()    // 현재 시각 기준으로 만료 판정
        );

        // count > 0일 때만 로그 출력 (매 분마다 "0개 처리" 로그 넘침 방지)
        if (count > 0) {
            log.info("만료 처리된 토큰: {}개", count);
        }
    }
}
```

**@Scheduled vs @SchedulerLock 역할 분리:**

```
@Scheduled(fixedDelay = 60_000)
→ Spring이 60초마다 이 메서드를 호출합니다.
→ 인스턴스가 2개면 두 인스턴스 모두 60초마다 호출합니다.

@SchedulerLock(name = "expireTokens")
→ ShedLock이 Redis에 "expireTokens" 락을 저장합니다.
→ 먼저 락을 잡은 인스턴스만 실제로 실행합니다.
→ 나머지 인스턴스는 락이 있으면 메서드 본문을 건너뜁니다.

결과: 60초마다 오직 1개의 인스턴스에서만 실행됩니다.
```

**lockAtMostFor이 필요한 이유:**

인스턴스가 배치 도중 갑자기 죽으면 락이 영구적으로 남습니다.
`lockAtMostFor = "PT55S"` → "55초 후에는 강제로 락을 해제해라"
다음 배치 주기(60초)에 다른 인스턴스가 락을 잡을 수 있게 합니다.

---

## 전체 데이터 흐름 정리

### Redis Pub/Sub 흐름

```
1. 유저 A → app1에 SSE 연결 (/api/v1/queue/subscribe)
   app1.SseEmitterStore: {"1:A" → SseEmitter}

2. 유저 B → app2에 SSE 연결
   app2.SseEmitterStore: {"1:B" → SseEmitter}

3. processQueue() - ShedLock에 의해 app1에서만 실행
   - userId=A, B를 Redis 대기열에서 꺼냄
   - 토큰 발급 후 Redis에 저장
   - Redis "queue:ready" 채널에 publish:
       {"eventId":1,"userId":"A","token":"xxx"}
       {"eventId":1,"userId":"B","token":"yyy"}

4. Redis → app1, app2 동시에 메시지 전달 (Pub/Sub 브로드캐스트)

5. app1.QueueReadySubscriber.onMessage():
   - userId=A → emitterStore.get(1, A) → SseEmitter 있음 → 전송 ✅
   - userId=B → emitterStore.get(1, B) → null → 무시

6. app2.QueueReadySubscriber.onMessage():
   - userId=A → emitterStore.get(1, A) → null → 무시
   - userId=B → emitterStore.get(1, B) → SseEmitter 있음 → 전송 ✅
```

### 토큰 만료 배치 흐름

```
발급 시점:
  Redis:  token:1:abc (TTL 300초)
  DB:     queue_token(token=abc, status=VALID, expiresAt=15:05:00)

5분 후 (Redis TTL 만료):
  Redis:  키 자동 삭제
  DB:     status=VALID (아직 갱신 안 됨)
  → 이 상태에서 클레임 시도 시 Redis에 토큰이 없어 UNAUTHORIZED 반환 (정상)

배치 실행 (60초마다):
  SELECT WHERE status=VALID AND expiresAt < NOW()
  UPDATE SET status=EXPIRED
  DB:     status=EXPIRED ✅ 일치
```

---

## 추가로 알면 좋은 것: SSE란?

**SSE (Server-Sent Events):** 서버에서 클라이언트로 단방향 스트리밍 통신.

```
클라이언트 → 서버: GET /api/v1/queue/subscribe (한 번 연결)
서버 → 클라이언트: 이벤트 발생할 때마다 데이터 전송
  event: ready
  data: {"token":"abc-def-..."}
```

WebSocket과 달리 클라이언트→서버 메시지가 없고, HTTP 기반이라 인프라 설정이 간단합니다.
이 프로젝트에서는 "토큰 발급됨" 알림 한 번만 보내면 되므로 SSE가 적합합니다.
