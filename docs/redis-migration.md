# Redis 전환기

인메모리(ConcurrentHashMap) 기반 대기열을 Redis 기반으로 전환한 과정을 기록한다.

---

## 배경 — 왜 전환했나

### 기존 구조의 한계

초기 구현은 JVM 프로세스 내부의 자료구조만으로 큐를 운영했다.

```java
// 이 자료구조들은 모두 JVM 힙 메모리 안에만 존재한다.
// 즉, 이 서버 프로세스가 죽거나 다른 서버 인스턴스가 생기면
// 각자 별개의 메모리를 갖기 때문에 데이터를 공유할 수 없다.
ConcurrentHashMap<Long, Long>   userSequence   // userId → 순번
ConcurrentHashMap<Long, String> userToken      // userId → 토큰
ConcurrentHashMap<String, Long> tokenStore     // 토큰 → userId
AtomicLong sequenceCounter   // 순번 발급 카운터 (이 JVM 안에서만 유일함)
AtomicLong processingCursor  // 처리된 마지막 순번
```

단일 서버에서는 동작하지만 세 가지 치명적인 문제가 있었다.

**① 다중 인스턴스 불가**

Nginx가 app1/app2 두 인스턴스에 요청을 분산하면 각자 별개의 메모리에 순번을 발급한다. userId=777이 app1에서 3번, app2에서 3번을 동시에 받는 상황이 발생한다.

```
app1: sequenceCounter = 1, 2, 3 ...   ← 완전히 다른 메모리
app2: sequenceCounter = 1, 2, 3 ...   ← 중복 순번 발생
```

**② 서버 재시작 시 큐 초기화**

배포나 장애로 인한 재시작 시 대기 중이던 유저 정보가 전부 사라진다.

**③ 스케줄러 중복 실행**

`@Scheduled`는 모든 인스턴스에서 동시에 실행된다. app1/app2가 각자 "10명 처리"를 하면 실제로는 20명이 처리되어 순서가 깨진다.

---

## 전환 목표

| 항목 | 전환 전 | 전환 후 |
|------|--------|--------|
| 순번 발급 | JVM AtomicLong (인스턴스별 독립) | Redis ZADD (공유) |
| 대기열 | ConcurrentHashMap | Redis Sorted Set |
| 토큰 저장 | ConcurrentHashMap (만료 없음) | Redis String + TTL |
| 서버 재시작 | 큐 초기화 | 유지 |
| 다중 인스턴스 | 상태 충돌 | 공유 상태 |
| 스케줄러 | 모든 인스턴스 실행 | ShedLock으로 1개만 실행 |
| 알림 방식 | 2초 폴링 | SSE 푸시 |
| 클레임 중복 방지 | 없음 | DB unique constraint |
| 토큰 이력 | 없음 | DB 영구 기록 |

---

## Phase 1 — Redis 인프라 연동

### 의존성 추가 (`build.gradle`)

```groovy
// Spring Data Redis: RedisTemplate, StringRedisTemplate 등 Redis 연동 도구 모음.
// 내부적으로 Lettuce 클라이언트를 포함하고 있어서 별도로 추가할 필요 없다.
// (Lettuce = Java에서 Redis 서버와 실제로 TCP 통신하는 라이브러리)
implementation 'org.springframework.boot:spring-boot-starter-data-redis'

// ShedLock 본체: @SchedulerLock 어노테이션과 분산 락 코어 로직 포함
implementation 'net.javacrumbs.shedlock:shedlock-spring:5.10.0'

// ShedLock의 Redis 저장소 어댑터.
// ShedLock은 락을 어디에 저장할지를 플러그인 방식으로 선택한다.
// Redis, MySQL, MongoDB 등 여러 선택지가 있고, 여기서는 이미 쓰고 있는 Redis를 선택한다.
implementation 'net.javacrumbs.shedlock:shedlock-provider-redis-spring:5.10.0'
```

### Redis 연결 설정 (`application-local.yml`)

```yaml
spring:
  data:
    redis:
      host: localhost   # Redis 서버 주소. 로컬에서는 Docker 컨테이너가 localhost:6379에 뜬다.
      port: 6379        # Redis 기본 포트. 변경하지 않았으면 항상 6379.
```

### RedisConfig — StringRedisTemplate 빈 등록

```java
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        // StringRedisTemplate: 키와 값을 모두 String으로 주고받는 Redis 클라이언트.
        // Spring이 기본 제공하는 RedisTemplate<Object, Object>도 있지만
        // 그건 Java 직렬화(바이트 배열)를 사용해서 Redis CLI로 데이터를 보면
        // "\xac\xed\x00\x05t\x00..." 같이 깨진 문자가 나온다.
        // StringRedisTemplate를 쓰면 redis-cli로 봐도 사람이 읽을 수 있는 형태로 나온다.
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory); // Redis 서버 연결 설정 주입

        // 아래 4줄은 "키와 값을 모두 일반 문자열로 저장하겠다"는 선언.
        // StringRedisTemplate은 기본적으로 이 설정이 적용되어 있지만
        // 명시적으로 써두면 나중에 설정을 바꿀 때 어디서 바꿔야 하는지 바로 보인다.
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());   // Hash 타입의 필드명
        template.setHashValueSerializer(new StringRedisSerializer()); // Hash 타입의 필드값
        return template;
    }
}
```

### SchedulerConfig — ShedLock + @EnableScheduling

```java
@Configuration
@EnableScheduling         // @Scheduled 어노테이션이 동작하도록 스케줄러를 활성화
@EnableSchedulerLock(defaultLockAtMostFor = "PT2S")
// PT2S = ISO 8601 기간 표기법 (Period Time 2 Seconds).
// defaultLockAtMostFor: 모든 @SchedulerLock에 기본으로 적용되는 최대 락 보유 시간.
// 락을 잡은 인스턴스가 실행 중에 프로세스가 죽어버리면 락이 영원히 풀리지 않을 수 있다.
// 이 값을 설정해두면 Redis에 저장된 락 키가 2초 후 자동으로 만료되어 다른 인스턴스가 락을 잡을 수 있다.
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory factory) {
        // ShedLock에게 "락 정보를 Redis에 저장해라"고 알려주는 설정.
        // 락을 잡을 때 Redis에 SETNX(Set if Not eXists) 방식으로 키를 만들고,
        // 락을 해제할 때 그 키를 삭제한다.
        // 여러 인스턴스가 동시에 SETNX를 시도해도 Redis는 단일 스레드라
        // 딱 하나만 성공하는 것이 보장된다.
        return new RedisLockProvider(factory);
    }
}
```

### docker-compose.dev.yml에 Redis 추가

```yaml
redis:
  image: redis:7.0-alpine  # alpine = 경량 리눅스 기반. 이미지 크기가 작다.
  ports:
    - "6379:6379"           # 호스트:컨테이너. 로컬에서 redis-cli로 직접 접속 가능하게 포트를 연다.
  command: >
    redis-server
    --appendonly yes
    # AOF(Append Only File) 모드 활성화.
    # Redis는 기본적으로 인메모리 DB라 컨테이너를 재시작하면 데이터가 사라진다.
    # appendonly yes를 켜면 모든 쓰기 명령을 /data/appendonly.aof 파일에 기록하고
    # 재시작 시 이 파일을 읽어서 데이터를 복원한다.
    --maxmemory 256mb
    # Redis가 사용할 수 있는 최대 메모리.
    # 이 용량을 초과하면 가장 오래된 키부터 삭제(evict)한다.
    # 설정하지 않으면 서버 메모리를 무한정 사용하다가 OOM(메모리 부족)으로 죽을 수 있다.
  volumes:
    - redis_dev_data:/data  # AOF 파일이 저장되는 /data 디렉토리를 Docker 볼륨에 마운트.
                            # 볼륨은 컨테이너를 삭제해도 데이터가 남아 있다.
```

---

## Phase 2 — 큐 서비스 Redis 전환

### Redis 키 설계

```
queue:waiting:{eventId}           Sorted Set   대기열 (score = 입장 timestamp)
user:token:{eventId}:{userId}     String       userId → 토큰 (TTL 300s)
token:{eventId}:{uuid}            String       토큰 → userId (TTL 300s)
```

키에 `{eventId}`를 포함시킨 이유: 이벤트가 여러 개일 때 키가 충돌하지 않고 이벤트별로 독립된 큐를 운영할 수 있다.

### Redis 키 메서드

```java
// Redis 키를 문자열 조합으로 만든다.
// Redis는 키에 계층 구조가 없고 모두 flat한 문자열이지만,
// 콜론(:)으로 구분하는 네이밍 컨벤션을 쓰면 redis-cli에서 --scan으로 패턴 검색이 가능하고
// RedisInsight 같은 GUI 툴에서는 콜론을 기준으로 폴더처럼 묶어서 보여준다.
private String waitingKey(Long eventId) {
    return "queue:waiting:" + eventId;       // ex) "queue:waiting:1"
}
private String userTokenKey(Long eventId, Long userId) {
    return "user:token:" + eventId + ":" + userId;  // ex) "user:token:1:777"
}
private String tokenKey(Long eventId, String token) {
    return "token:" + eventId + ":" + token; // ex) "token:1:550e8400-..."
}
```

### enter() — ZADD로 대기열 진입

```java
public EnterResponse enter(Long userId, Long eventId) {
    String key = waitingKey(eventId); // "queue:waiting:1"

    // ZADD 명령: Sorted Set에 member(userId)를 score(입장시각)와 함께 추가한다.
    // Sorted Set은 score 기준 오름차순으로 항상 정렬된 상태를 유지한다.
    // 입장 시각(Unix ms)을 score로 쓰면 먼저 들어온 유저가 앞에 있게 된다.
    //
    // addIfAbsent = Redis의 ZADD NX 옵션.
    // NX(Not eXists): 이미 이 member가 Set에 있으면 아무것도 하지 않는다.
    // 덕분에 같은 유저가 네트워크 오류로 두 번 호출해도 순번이 바뀌지 않는다.
    // (NX 없이 ZADD를 쓰면 score가 현재 시각으로 덮어씌워져 줄 맨 뒤로 밀려난다.)
    redis.opsForZSet().addIfAbsent(key, userId.toString(), System.currentTimeMillis());

    // 이미 토큰이 발급된 유저(대기열을 통과한 유저)가 다시 enter를 호출한 경우.
    // 이 유저는 대기열에서 이미 ZPOPMIN으로 꺼내진 상태라 ZRANK로 순위를 구할 수 없다.
    // 0을 반환해서 프론트가 "이미 통과됨"을 인식하게 한다.
    if (redis.hasKey(userTokenKey(eventId, userId))) {
        return new EnterResponse(0);
    }

    // ZRANK 명령: Sorted Set에서 이 member의 순서(0-based 인덱스)를 반환한다.
    // 첫 번째 유저면 0, 두 번째면 1을 반환한다.
    // null은 Set에 member가 아예 없는 경우(방금 addIfAbsent가 실패한 극단적 상황).
    Long rank = redis.opsForZSet().rank(key, userId.toString());

    // Redis ZRANK는 0부터 시작하지만 유저에게 보여줄 때는 1부터 시작하는 게 자연스럽다.
    // "0번째 대기" 보다 "1번째 대기"가 맞는 표현이므로 +1 보정.
    return new EnterResponse(rank == null ? 1 : rank + 1);
}
```

### getStatus() — ZRANK로 순위 조회

```java
public StatusResponse getStatus(Long userId, Long eventId) {
    // 먼저 토큰 발급 여부를 확인한다.
    // GET 명령: 단순 String 키의 값을 가져온다. 없으면 null.
    // user:token:1:777 키가 존재한다 = 이 유저는 이미 대기열을 통과해서 토큰을 받았다.
    String token = redis.opsForValue().get(userTokenKey(eventId, userId));
    if (token != null) {
        // 토큰이 있으면 즉시 ready 상태 반환. ZRANK를 조회할 필요도 없다.
        return StatusResponse.ready(token);
    }

    // 토큰이 없으면 아직 대기 중. Sorted Set에서 현재 순위를 가져온다.
    // ZRANK: 해당 member의 현재 위치(0-based).
    // 앞에 있던 유저들이 ZPOPMIN으로 꺼내지면 이 값이 자동으로 줄어든다.
    // 별도로 "순위 업데이트" 로직을 짤 필요 없이 ZRANK 한 번 호출로 항상 최신 순위를 얻는다.
    Long rank = redis.opsForZSet().rank(waitingKey(eventId), userId.toString());

    // rank == null: 유저가 대기열에 없는 경우(enter를 한 번도 안 했거나 이미 처리 완료).
    // 0 반환 시 프론트에서 "대기열 정보 없음"으로 처리한다.
    return StatusResponse.waiting(rank == null ? 0 : rank + 1); // 0-based → 1-based
}
```

### processQueue() — ZPOPMIN으로 원자적 처리

```java
@Scheduled(fixedDelay = 1000) // 이전 실행이 끝난 후 1000ms 후에 다시 실행
@SchedulerLock(
    name = "processQueue",       // Redis에 저장될 락 키 이름
    lockAtMostFor = "PT2S",      // 최대 2초 락 유지. 인스턴스가 죽어도 2초 후 자동 해제.
    lockAtLeastFor = "PT1S"      // 최소 1초 락 유지. 실행이 1ms에 끝나도 1초간 다른 인스턴스가 못 잡음.
                                 // 이게 없으면 fixedDelay=1000이어도 락 해제 직후 다른 인스턴스가
                                 // 즉시 락을 잡아서 거의 동시에 두 번 실행될 수 있다.
)
public void processQueue() {
    Long eventId = 1L;

    // ZPOPMIN N 명령: Sorted Set에서 score가 가장 낮은(가장 먼저 들어온) N개를
    // 꺼내면서 동시에 Set에서 삭제한다.
    //
    // 이게 핵심이다. "조회 후 삭제"가 아니라 "꺼내기(pop)"가 원자적으로 일어난다.
    // Redis는 단일 스레드로 명령을 처리하기 때문에 ZPOPMIN은 중간에 끊기지 않는다.
    // app1과 app2가 동시에 ZPOPMIN 10을 호출해도 각자 다른 10명씩 꺼낸다.
    // 같은 유저가 두 번 꺼내지는 일이 절대 없다.
    //
    // 기존 ConcurrentHashMap + AtomicLong 방식은 JVM 레벨 원자성이라
    // 여러 JVM 프로세스 간에는 보장되지 않았다.
    Set<ZSetOperations.TypedTuple<String>> users =
            redis.opsForZSet().popMin(waitingKey(eventId), PROCESS_PER_SECOND);

    // 대기열이 비어있으면(아무도 입장 안 했으면) 아무것도 하지 않고 종료.
    if (users == null || users.isEmpty()) return;

    for (ZSetOperations.TypedTuple<String> entry : users) {
        // TypedTuple: ZPOPMIN이 반환하는 객체. getValue()로 member, getScore()로 score를 얻는다.
        // member = userId를 String으로 저장했으므로 다시 Long으로 변환.
        Long userId = Long.valueOf(entry.getValue());
        String token = UUID.randomUUID().toString(); // 36자리 UUID (8-4-4-4-12 형식)

        // SET key value EX seconds 명령: 키에 값을 저장하면서 TTL(만료 시간)을 함께 설정.
        // EX 300 = 300초(5분) 후 Redis가 이 키를 자동으로 삭제한다.
        // 별도의 만료 배치나 스케줄러 없이도 5분이 지나면 토큰이 사라진다.
        // ConcurrentHashMap을 쓰던 시절에는 만료 로직을 직접 구현해야 했다.

        // ③ userId → 토큰: getStatus() 호출 시 "이 유저 토큰 발급됐나?" 조회용
        redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);

        // ④ 토큰 → userId: Claim API에서 "이 토큰 누구 건가?" 역방향 조회용
        redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL);

        // DB에도 영구 기록. Redis는 TTL로 사라지지만 DB는 남아서 감사 로그로 쓸 수 있다.
        queueTokenRepository.save(QueueToken.of(eventId, userId, token));

        // SSE로 해당 유저에게 즉시 알림. "차례가 됐습니다 + 토큰"을 push.
        pushReady(eventId, userId, token);
    }
}
```

---

## Phase 3 — 폴링 → SSE 푸시 전환

### 왜 바꿨나

2초 폴링 방식은 유저 수가 늘수록 서버 부하가 선형으로 증가한다. 유저 1000명이면 초당 500건의 요청이 순수하게 "아직 아닌가요?" 확인에만 소비된다. SSE는 커넥션을 열어두고 서버가 이벤트가 생겼을 때만 데이터를 밀어주므로, 토큰 발급 전까지 서버 CPU를 사용하지 않는다.

### 백엔드 — SseEmitter

Spring MVC의 `SseEmitter`는 HTTP 응답을 즉시 완료하지 않고 커넥션을 열어두다가 나중에 데이터를 전송할 수 있는 비동기 응답 객체다.

**SseEmitterStore**:

```java
@Component
public class SseEmitterStore {

    // ConcurrentHashMap: 멀티스레드 환경에서 안전한 HashMap.
    // 스케줄러 스레드(토큰 발급)와 HTTP 요청 스레드(구독 등록)가 동시에 접근하므로
    // 일반 HashMap은 쓸 수 없다.
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 키를 "eventId:userId" 형태의 단일 문자열로 만든다.
    // Map의 키로 두 값을 조합하는 가장 단순한 방법.
    private String key(Long eventId, Long userId) {
        return eventId + ":" + userId;
    }

    public void put(Long eventId, Long userId, SseEmitter emitter) {
        emitters.put(key(eventId, userId), emitter);
    }

    public SseEmitter get(Long eventId, Long userId) {
        return emitters.get(key(eventId, userId));
    }

    public void remove(Long eventId, Long userId) {
        emitters.remove(key(eventId, userId));
    }
}
```

> SseEmitterStore는 인메모리지만 이건 괜찮다. Emitter는 특정 HTTP 커넥션에 묶여 있는 객체라 어차피 해당 인스턴스에만 의미가 있다. 유저가 app1에 연결됐으면 app1의 SseEmitterStore에만 저장되어 있고, 토큰 발급도 app1의 스케줄러(ShedLock이 락을 잡은 인스턴스)에서 이루어지면 app1의 emitterStore에서 찾아 전송한다. 단, ShedLock으로 락을 잡은 인스턴스가 app2인데 유저가 app1에 연결돼 있으면 SSE가 전달되지 않는다는 한계가 있다. 이를 완전히 해결하려면 Redis Pub/Sub으로 인스턴스 간 이벤트를 중계해야 한다 (현재 미구현).

**SseController**:

```java
@GetMapping("/subscribe")
public SseEmitter subscribe(@RequestParam Long userId, @RequestParam Long eventId) {
    // SseEmitter(timeoutMs): 이 숫자만큼 ms가 지나도 이벤트가 오지 않으면 커넥션을 끊는다.
    // 300_000ms = 5분. 토큰 TTL과 맞춘 것.
    // 5분이 지나도록 토큰을 못 받으면 이미 만료된 상황이므로 커넥션 유지 의미가 없다.
    // 숫자에 언더스코어를 쓴 건 Java의 숫자 리터럴 구분자로, 300000보다 읽기 쉽다.
    SseEmitter emitter = new SseEmitter(300_000L);

    // 유저의 emitter를 저장소에 등록. 나중에 스케줄러가 여기서 꺼내 메시지를 보낸다.
    emitterStore.put(eventId, userId, emitter);

    // 커넥션이 끊기는 3가지 경우에 저장소에서 제거하는 콜백을 등록한다.
    // 제거하지 않으면 끊긴 커넥션의 emitter가 메모리에 계속 쌓인다(메모리 누수).
    emitter.onCompletion(() -> emitterStore.remove(eventId, userId)); // 정상 완료(complete() 호출)
    emitter.onTimeout(() -> emitterStore.remove(eventId, userId));    // 타임아웃 발생
    emitter.onError(e -> emitterStore.remove(eventId, userId));       // 네트워크 오류 등

    // SseEmitter를 그냥 반환하면 Spring이 응답을 바로 닫지 않고 열린 상태로 유지한다.
    // Content-Type: text/event-stream 헤더가 자동으로 붙는다.
    return emitter;
}
```

**processQueue()에서 SSE 전송**:

```java
private void pushReady(Long eventId, Long userId, String token) {
    // 이 유저가 SSE 구독을 하고 있는지 확인한다.
    // 구독 안 한 유저(앱을 종료했거나 폴링만 쓰는 경우)면 null이 반환된다.
    SseEmitter emitter = emitterStore.get(eventId, userId);
    if (emitter == null) return; // SSE 구독 없으면 조용히 건너뜀. 폴링 fallback이 처리한다.

    try {
        emitter.send(
            SseEmitter.event()
                .name("ready")               // 이벤트 이름. 프론트에서 addEventListener("ready", ...)로 받는다.
                .data(Map.of("token", token)) // 페이로드. JSON {"token": "uuid-..."} 형태로 직렬화된다.
        );
        emitter.complete(); // 전송 완료. 커넥션을 정상 종료한다.
                            // complete()를 호출하면 onCompletion 콜백이 실행되어 저장소에서도 제거된다.
    } catch (IOException e) {
        // 전송 중 네트워크 오류. 유저가 앱을 강제 종료하거나 와이파이가 끊긴 경우.
        // 예외를 위로 던지지 않고 여기서 처리하는 이유:
        // 이 메서드는 스케줄러 안에서 10명 루프를 돌면서 호출된다.
        // 한 명의 SSE 실패가 나머지 9명의 토큰 발급을 막으면 안 된다.
        log.warn("SSE 전송 실패 userId={}", userId);
        emitterStore.remove(eventId, userId); // 고장난 emitter 제거
    }
}
```

### 프론트엔드 — EventSource로 교체

```typescript
useEffect(() => {
  // EventSource: 브라우저/React Native 내장 Web API.
  // 이 URL로 GET 요청을 보내면 서버가 응답을 바로 닫지 않고 열어둔다.
  // 서버가 "data: {...}\n\n" 형식으로 데이터를 보낼 때마다 이벤트가 발생한다.
  // HTTP 프로토콜을 그대로 쓰되 응답을 스트리밍으로 받는 방식이다.
  const es = new EventSource(
    `${BASE_URL}/api/v1/queue/subscribe?userId=${userId}&eventId=${eventId}`
  );

  // 서버에서 name="ready" 이벤트가 오면 이 콜백이 실행된다.
  // 백엔드의 emitter.send(SseEmitter.event().name("ready").data(...))에 대응한다.
  es.addEventListener('ready', (e: MessageEvent) => {
    const { token } = JSON.parse(e.data); // 서버가 보낸 JSON 파싱
    navigation.replace('Ready', { token, sequenceNumber: rank });
    es.close(); // 받았으면 커넥션 닫기. 안 닫으면 서버 emitter가 타임아웃까지 유지된다.
  });

  // 네트워크 오류나 서버 다운 시 호출된다.
  // 기본적으로 EventSource는 오류 발생 시 자동으로 재연결을 시도한다.
  // 여기서는 재연결 없이 그냥 닫기로 한다. 폴링 fallback이 토큰을 가져올 수 있다.
  es.onerror = () => es.close();

  // useEffect cleanup: 화면을 벗어날 때 커넥션을 닫는다.
  // 안 닫으면 다른 화면으로 이동해도 서버에 커넥션이 남아 있어 리소스를 낭비한다.
  return () => es.close();
}, [userId, eventId]); // userId, eventId가 바뀌면 커넥션을 새로 연다. 보통은 바뀌지 않음.

// 화면에 표시되는 "몇 번째" 숫자를 업데이트하기 위한 폴링.
// SSE는 알림 전용이고, 순위 숫자는 주기적으로 서버에 물어봐야 한다.
// 2초 → 10초로 늘린 이유: 화면 숫자가 1~2초 늦게 업데이트되어도 UX에 큰 영향 없고,
// 서버 부하를 줄이는 것이 더 중요하다.
useEffect(() => {
  const poll = async () => {
    try {
      const status = await queueService.getStatus(userId, eventId);
      if (status.rank > 0) setRank(status.rank); // rank가 0이면 이미 처리 완료, 업데이트 불필요
    } catch (e) {} // 네트워크 오류는 무시. 다음 폴링 때 다시 시도.
  };

  const interval = setInterval(poll, 10000); // 10초마다
  return () => clearInterval(interval); // 화면 벗어날 때 정리
}, [userId, eventId]);
```

---

## Phase 4 — DB 영구 기록

Redis는 캐시라 장애, 재시작, maxmemory 초과 시 데이터가 사라질 수 있다. 토큰 발급 이력은 DB에 영구 보관한다.

### 테이블 구조

**queue_token** — 토큰 발급 이력

```sql
CREATE TABLE queue_token (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id   BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    token      VARCHAR(36) NOT NULL UNIQUE, -- UUID는 항상 36자리 (8-4-4-4-12 + 하이픈)
    issued_at  TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status     ENUM('VALID', 'USED', 'EXPIRED') DEFAULT 'VALID',

    -- (event_id, user_id) 쌍의 유일성을 DB 레벨에서 강제.
    -- 같은 이벤트에서 한 유저가 토큰을 두 개 받으려 하면 INSERT 자체가 실패한다.
    -- 애플리케이션 코드에서 체크하는 것보다 훨씬 안전하다.
    -- (애플리케이션 체크는 동시 요청이 들어오면 둘 다 통과할 수 있지만 DB constraint는 불가능)
    UNIQUE KEY uq_event_user (event_id, user_id)
);
```

### JPA 엔티티

```java
@Entity
@Table(
    name = "queue_token",
    // DB의 UNIQUE KEY uq_event_user와 같은 제약을 JPA 레벨에서도 선언.
    // 이렇게 하면 Hibernate가 스키마를 자동 생성할 때(ddl-auto: create/update) 이 제약도 만들어준다.
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// AccessLevel.PROTECTED: 외부에서 new QueueToken()으로 직접 생성 불가.
// 반드시 of() 팩토리 메서드를 통해서만 생성하도록 강제한다.
// 실수로 필드가 누락된 객체를 저장하는 버그를 컴파일 타임에 막을 수 있다.
public class QueueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // AUTO_INCREMENT
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    // EnumType.STRING: DB에 "VALID", "USED", "EXPIRED" 문자열로 저장.
    // EnumType.ORDINAL(기본값)은 0, 1, 2 숫자로 저장하는데,
    // 나중에 enum 순서가 바뀌면 의미가 달라지는 위험한 방식이라 STRING을 권장한다.
    @Column(nullable = false, length = 10)
    private TokenStatus status;

    // 팩토리 메서드: 필수 필드만 받아서 올바른 초기 상태의 객체를 만든다.
    // issued_at, expires_at, status는 항상 고정값이므로 외부에서 받을 필요가 없다.
    public static QueueToken of(Long eventId, Long userId, String token) {
        QueueToken qt = new QueueToken();
        qt.eventId = eventId;
        qt.userId = userId;
        qt.token = token;
        qt.issuedAt = LocalDateTime.now();
        qt.expiresAt = qt.issuedAt.plusSeconds(300); // 발급 시각 + 5분
        qt.status = TokenStatus.VALID;
        return qt;
    }

    // 상태 변경 메서드를 엔티티 안에 둔 이유:
    // "토큰을 소진한다"는 도메인 규칙이 엔티티 안에 있어야 일관성이 유지된다.
    // 외부에서 qt.setStatus(TokenStatus.USED)로 직접 바꾸는 것보다
    // qt.markUsed()가 "이 메서드가 하는 일"을 명확하게 표현한다.
    public void markUsed() {
        this.status = TokenStatus.USED;
    }
}
```

### 토큰 발급 흐름 (Redis + DB 동시 저장)

```java
// Redis와 DB에 모두 저장한다. 역할이 다르다.
//
// Redis: 빠른 조회 전용 캐시. Claim API가 토큰 검증 시 DB 대신 여기를 먼저 본다.
//        Redis 조회는 수 마이크로초, DB 조회는 수 밀리초. 대용량 트래픽에서 차이가 크다.
//        TTL이 지나면 자동 삭제 → 만료된 토큰은 검증 실패로 처리된다.
//
// DB: 영구 보관. Redis가 날아가도 "누가 언제 토큰을 받았는가" 기록이 남는다.
//     관리자가 이상 거래를 조회하거나 고객 문의에 대응할 때 쓴다.

// ③ userId로 토큰 조회 (getStatus 폴링용)
redis.opsForValue().set(userTokenKey(eventId, userId), token, TOKEN_TTL);

// ④ 토큰으로 userId 조회 (Claim 검증용 역방향 인덱스)
redis.opsForValue().set(tokenKey(eventId, token), userId.toString(), TOKEN_TTL);

// DB INSERT. JPA save()가 INSERT SQL을 실행한다.
queueTokenRepository.save(QueueToken.of(eventId, userId, token));
```

---

## Phase 5 — Claim API + 중복 방지

### 클레임 처리 흐름

```
POST /api/v1/claim
{ "userId": 777, "eventId": 1, "token": "uuid-..." }
        │
        ▼
1. Redis에서 token:{eventId}:{token} 조회
   → null이면 401 (만료 또는 존재하지 않는 토큰)
   → userId 불일치면 403 (다른 사람의 토큰)
        │
        ▼
2. claim 테이블에 INSERT
   → Duplicate Key 예외면 409 (이미 수령)
        │
        ▼
3. Redis에서 토큰 키 삭제 (재사용 방지)
   DB에서 QueueToken status → USED
        │
        ▼
4. 200 OK
```

### ClaimService

```java
@Transactional
// @Transactional: 이 메서드 안의 모든 DB 작업이 하나의 트랜잭션으로 묶인다.
// claim INSERT와 queue_token UPDATE 중 하나라도 실패하면 둘 다 롤백된다.
// Redis 작업은 트랜잭션 밖이라 롤백되지 않는다는 점을 주의. (Redis는 별도 처리 필요)
public ClaimResponse claim(ClaimRequest request) {
    // "token:1:uuid-..." 형태의 키. 이 키의 값이 userId다.
    String tokenRedisKey = "token:" + request.eventId() + ":" + request.token();

    // 1단계: Redis 빠른 검증
    // Redis GET은 O(1)에 수 마이크로초. DB SELECT보다 100배 이상 빠르다.
    // 만료된 토큰, 존재하지 않는 토큰을 DB 조회 없이 즉시 걸러낸다.
    String storedUserId = redis.opsForValue().get(tokenRedisKey);

    if (storedUserId == null) {
        // null인 경우 두 가지:
        // A) 애초에 발급된 적 없는 토큰 (위조 시도)
        // B) TTL 300초가 지나서 Redis에서 자동 삭제된 토큰 (만료)
        // 둘 다 401로 처리한다.
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
    }

    if (!storedUserId.equals(request.userId().toString())) {
        // 토큰은 존재하지만 요청한 userId와 토큰에 저장된 userId가 다른 경우.
        // 다른 사람의 토큰을 탈취해서 자기 userId로 클레임하는 시도.
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "토큰 소유자가 다릅니다.");
    }

    // 2단계: DB INSERT + 중복 방지
    // Redis 검증을 통과했다고 해서 중복 클레임이 완전히 방지된 건 아니다.
    // 극히 드문 race condition 시나리오:
    //   1) 요청 A, B가 거의 동시에 도착
    //   2) 둘 다 Redis에서 토큰을 조회하고 유효하다고 판단 (아직 DELETE 전)
    //   3) 둘 다 DB INSERT를 시도
    //   → 이 경우 DB unique constraint가 하나를 막아준다.
    try {
        claimRepository.save(Claim.of(request.eventId(), request.userId(), request.token()));
    } catch (DataIntegrityViolationException e) {
        // (event_id, user_id) unique constraint 위반. 이미 클레임한 유저.
        // DB가 중복을 막아주는 최종 방어선.
        throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 수령하셨습니다.");
    }

    // 3단계: 토큰 소진 처리
    // Redis에서 토큰 키 삭제. 이 시점 이후 같은 토큰으로 재시도하면 1단계에서 막힌다.
    redis.delete(tokenRedisKey);

    // DB에서 토큰 상태를 VALID → USED로 변경. 감사 로그 용도.
    // ifPresent: DB에서 토큰을 못 찾아도 예외를 던지지 않는다.
    // (Redis TTL이 짧아서 DB 기록이 있는데 Redis 키가 없는 상황은 사실상 없지만 방어 코드)
    queueTokenRepository.findByToken(request.token())
            .ifPresent(QueueToken::markUsed); // qt -> qt.markUsed() 와 동일

    return ClaimResponse.ok();
}
```

### claim 테이블

```sql
CREATE TABLE claim (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    token       VARCHAR(36) NOT NULL,
    claimed_at  TIMESTAMP NOT NULL,

    -- 이 제약이 중복 클레임 방지의 최종 보루.
    -- 아무리 동시에 요청이 쏟아져도 DB 레벨에서 한 유저당 한 번만 INSERT를 허용한다.
    UNIQUE KEY uq_event_user (event_id, user_id)
);
```

---

## 전환 후 전체 구조

```
[유저]
  │
  ├─ POST /api/v1/queue/enter
  │     → Redis ZADD queue:waiting:1 <timestamp> <userId>
  │     → ZRANK로 현재 순위 반환
  │
  ├─ GET /api/v1/queue/subscribe  (SSE 커넥션 유지)
  │     → SseEmitterStore에 emitter 등록
  │
  │        [Spring @Scheduled 1초마다 — ShedLock으로 1개 인스턴스만 실행]
  │              ZPOPMIN queue:waiting:1  10명
  │              ↓
  │        Redis SET user:token:1:{userId}  <token>  EX 300
  │        Redis SET token:1:{token}        <userId> EX 300
  │        MySQL INSERT queue_token
  │        SseEmitter.send("ready", {token})
  │              ↓
  ├─ SSE 'ready' 이벤트 수신 → ReadyScreen으로 이동
  │
  └─ POST /api/v1/claim
        → Redis GET token:1:{token}   (유효성 검증)
        → MySQL INSERT claim          (중복 방지)
        → Redis DELETE token:1:{token}
        → MySQL UPDATE queue_token SET status = 'USED'
```

---

## Redis 키 실제 확인

```bash
# Redis 컨테이너에 접속해서 모든 키 목록 확인
docker exec fcfs-claim-redis-1 redis-cli KEYS "*"
```

```
job-lock:default:processQueue       ← ShedLock이 잡아둔 분산 락 키
queue:waiting:1                     ← 대기열 Sorted Set (ZPOPMIN으로 꺼낸 유저는 여기서 사라짐)
user:token:1:777                    ← userId 777에게 발급된 토큰
token:1:21f7c3bc-11d1-4fef-...      ← 위 토큰의 역방향 인덱스
```

```bash
# Sorted Set에 현재 대기 중인 유저 수
# ZCARD: Sorted Set의 전체 member 수를 반환
docker exec fcfs-claim-redis-1 redis-cli ZCARD queue:waiting:1

# 특정 유저의 현재 순위 (0-based, 앞에서부터)
# ZRANK: 해당 member의 인덱스 반환. 0이면 맨 앞, null이면 Set에 없음.
docker exec fcfs-claim-redis-1 redis-cli ZRANK queue:waiting:1 "777"

# 토큰이 몇 초 후 만료되는지 확인
# TTL: 해당 키의 남은 만료 시간(초). -1이면 TTL 없음, -2면 키가 없음.
docker exec fcfs-claim-redis-1 redis-cli TTL "user:token:1:777"

# Sorted Set의 전체 내용 확인 (member와 score 함께 출력)
# ZRANGE ... WITHSCORES: score가 낮은 것부터 모두 출력. score가 입장 timestamp이므로
# 출력 순서 = 대기 순서
docker exec fcfs-claim-redis-1 redis-cli ZRANGE queue:waiting:1 0 -1 WITHSCORES
```

---

## 남은 한계

| 항목 | 현재 상태 | 해결 방법 |
|------|---------|---------|
| SSE 다중 인스턴스 | 락을 잡은 인스턴스와 유저가 연결된 인스턴스가 다르면 SSE 미전달 | Redis Pub/Sub으로 인스턴스 간 중계 |
| 재고 차감 | 미구현 | `event` 테이블에 stock 컬럼 추가 후 `UPDATE ... WHERE stock > 0` |
| 토큰 만료 처리 | Redis TTL로 자동 삭제되지만 DB status는 `VALID` 유지 | 배치로 `expires_at < NOW()` 인 레코드를 `EXPIRED`로 업데이트 |
| 부하 테스트 | 미진행 | k6로 `/queue/enter` 대량 동시 요청 후 Redis ZCARD, 처리 지연 측정 |
