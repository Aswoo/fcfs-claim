# 테스트 코드 가이드

## 전략

```
단위 테스트 (Mockito)      → 서비스 비즈니스 로직. Redis/DB 전부 Mock
Repository 테스트          → JPA 커스텀 쿼리. H2 실제 실행
Controller 슬라이스 테스트  → HTTP 계약. MockMvc, 서비스 @MockBean
```

통합 테스트(`@SpringBootTest`)는 Redis 실서버가 필요하고 무거워서 제외했다.

---

## 파일 구조

```
src/test/java/com/example/fcfsclaim/
├── domain/
│   ├── claim/
│   │   ├── service/    ClaimServiceTest.java
│   │   └── controller/ ClaimControllerTest.java
│   ├── queue/
│   │   ├── service/    QueueServiceTest.java
│   │   │               QueueReadySubscriberTest.java
│   │   │               QueueEndedSubscriberTest.java
│   │   │               SseEmitterStoreTest.java
│   │   ├── repository/ QueueTokenRepositoryTest.java
│   │   └── controller/ QueueControllerTest.java
│   │                   SseControllerTest.java
│   ├── product/
│   │   ├── repository/ ProductRepositoryTest.java
│   │   └── controller/ ProductControllerTest.java
│   └── event/
│       ├── service/    EventLifecycleServiceTest.java
│       │               EventLifecycleListenerTest.java
│       │               EventRecoveryServiceTest.java
│       │               ActiveEventCacheTest.java
│       ├── repository/ EventRepositoryTest.java
│       └── controller/ EventControllerTest.java
└── FcfsClaimApplicationTests.java
```

---

## 실행 방법

```bash
# 전체 테스트 + 커버리지 리포트 생성
./gradlew test

# 커버리지 리포트 열기
open backend/build/reports/jacoco/test/html/index.html

# 특정 클래스만
./gradlew test --tests "*.ClaimServiceTest"
```

현재 커버리지: Instruction **75%** / Branch **55%** / Classes **88%**

---

## 커밋 전 자동 실행 (pre-push 훅)

```bash
# clone 후 1회 실행
make hooks
```

`git push` 시 `./gradlew test`가 자동 실행된다. 실패하면 push가 차단된다. 훅 파일은 `.githooks/pre-push`에 있고 `make hooks`가 `git config core.hooksPath .githooks`를 등록한다.

---

## ClaimServiceTest — 비즈니스 핵심

**방식:** `@ExtendWith(MockitoExtension.class)` — `StringRedisTemplate`, `ClaimRepository`, `ProductRepository`, `QueueTokenRepository` 모두 Mock

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `claim_성공` | 유효 토큰 + 재고 있음 | `save()`, `decrementStock()`, `redis.delete()` 호출 |
| `claim_토큰없음_401` | Redis 키 없음 | `UNAUTHORIZED` 예외 |
| `claim_토큰소유자불일치_403` | 다른 userId | `FORBIDDEN` 예외 |
| `claim_중복수령_409` | `save()` → `DataIntegrityViolationException` | `CONFLICT` |
| `claim_재고소진_409` | `decrementStock()` → 0 반환 | `CONFLICT` |
| `claim_재고소진시_Redis토큰_미삭제` | 재고 소진 → 예외 | `redis.delete()` **호출 안 됨** |

마지막 케이스가 핵심이다. 재고 소진 시 `@Transactional`이 롤백되는데, 이 시점에 Redis 토큰 삭제는 아직 실행 전이어야 한다.

---

## QueueServiceTest

**방식:** `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(LENIENT)`

`@LENIENT` 이유: `@BeforeEach`에서 공통 스텁을 설정하는데 일부 테스트에서 사용하지 않아 strict 모드가 "불필요한 스텁" 에러를 내기 때문이다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `enter_신규입장_rank반환` | 처음 입장 | `ZADD NX` 호출, rank 반환 |
| `enter_중복입장_기존rank반환` | `user:token:` 키 있음 | 기존 rank 반환 |
| `getStatus_대기중` | 토큰 없고 ZSET에 있음 | `isReady=false`, rank 반환 |
| `getStatus_토큰발급완료` | `user:token:` 키 있음 | `isReady=true`, 토큰 반환 |
| `validateToken_유효` | `token:` 키 있음 | `true` |
| `validateToken_만료` | `token:` 키 없음 | `false` |

---

## EventRepositoryTest — JPQL 쿼리 검증

**방식:** `@DataJpaTest` — H2 인메모리 DB

`EventRecoveryService`가 의존하는 두 JPQL 쿼리의 날짜 경계 조건을 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `findActivatable_start_at_지났고_end_at_안지난_SCHEDULED_반환` | 활성화 대상 1개 + 제외 대상 2개 | 대상만 반환 |
| `findActivatable_end_at_지난_SCHEDULED_제외` | end_at 경과 → activatable 아님 | empty |
| `findOverdue_end_at_지난_SCHEDULED_ACTIVE_반환` | SCHEDULED·ACTIVE 각 1개 + 제외 2개 | 2개 반환 |
| `findOverdue_end_at_미래면_제외` | end_at 미래 ACTIVE | empty |

---

## QueueTokenRepositoryTest — JPQL 쿼리 검증

**방식:** `@DataJpaTest` — H2 인메모리 DB, `TestEntityManager`로 1차 캐시 무효화

`@Modifying` JPQL 실행 후 `em.clear()`를 호출해 1차 캐시를 비워야 실제 DB 상태를 읽을 수 있다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `expireOverdue_만료된_토큰만_EXPIRED_처리` | 만료 1개 + 유효 1개 | count=1, 만료만 EXPIRED |
| `expireOverdue_만료_토큰_없으면_0반환` | 유효 토큰만 | count=0 |
| `expireOverdue_이미_USED인_토큰은_건드리지_않음` | USED 토큰 | count=0, 상태 변경 없음 |
| `expireOverdue_복수_만료_토큰_모두_처리` | 만료 2개 + 유효 1개 | count=2 |

---

## QueueReadySubscriberTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `SseEmitterStore`, `ObjectMapper` Mock

Redis `queue:ready` 채널 수신 후 SSE 전송 흐름을 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `정상_emitter에_token_전송_후_complete` | 정상 흐름 | `emitter.send()` + `emitter.complete()` |
| `emitter가_null이면_전송_안함` | emitter 없음 | `emitterStore.remove()` 호출 안 됨 |
| `send_IOException_발생시_emitter_제거` | 전송 중 연결 끊김 | `emitterStore.remove()` 호출 |
| `잘못된_JSON_예외처리_emitter_미호출` | JSON 파싱 실패 | `emitterStore.get()` 호출 안 됨 |

---

## QueueEndedSubscriberTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `EventLifecycleService` Mock

Redis `queue:ended` 채널 수신 후 `notifyEventEnded()` 호출 흐름을 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `정상_eventId_파싱_후_notifyEventEnded_호출` | 유효한 eventId 문자열 | `lifecycleService.notifyEventEnded(42L)` 호출 |
| `잘못된_메시지_예외_발생해도_notifyEventEnded_미호출` | 숫자 아닌 문자열 | 예외 삼키고 `notifyEventEnded()` 미호출 |

---

## SseEmitterStoreTest

**방식:** 순수 단위 테스트 — `SseEmitter` Mock

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `재연결시_이전_emitter_complete_미호출` | 재연결 시 first 덮어씀 | `first.complete()` 호출됨 (BUG-04 재현) |
| `재연결_후_새_emitter만_저장` | 재연결 후 조회 | second만 반환 |
| `put_get_remove_정상동작` | 기본 CRUD | 각 단계 검증 |
| `getByEventId_해당이벤트만_반환` | 다른 이벤트 섞임 | eventId 기준 필터링 |
| `remove_후_getByEventId_제외` | remove 후 조회 | empty |

---

## ProductRepositoryTest — 동시성 쿼리

**방식:** `@DataJpaTest` — H2 인메모리 DB

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `decrementStock_재고있음` | stock=3 차감 | `affectedRows=1`, stock=2 |
| `decrementStock_재고없음` | stock=0 차감 시도 | `affectedRows=0`, stock=0 유지 |
| `decrementStock_동시100건_재고3개` | 100스레드 동시 | `stock=0`, `successCount=3` |

동시성 테스트에서 `@Transactional(NOT_SUPPORTED)`를 쓰는 이유: `@DataJpaTest` 기본 트랜잭션이 메인 스레드를 감싸면 100개 자식 스레드는 커밋되지 않은 `@BeforeEach` 데이터를 볼 수 없다. NOT_SUPPORTED로 래핑을 끄면 BeforeEach 데이터가 자체 트랜잭션으로 커밋되어 모든 스레드에서 보인다.

---

## EventLifecycleServiceTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `EventRepository`, `ApplicationEventPublisher`, `SseEmitterStore`, `LockProvider` Mock

`ActiveEventCache`와 `StringRedisTemplate`은 이 서비스의 의존성에서 제거됐다. 상태 변경 후처리는 `EventLifecycleListener`가 담당하므로, 서비스 테스트에서는 `publishEvent()` 호출 여부만 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `activateEvent_성공` | 락 획득 + SCHEDULED 이벤트 | `publishEvent(EventActivatedEvent)` 호출 |
| `activateEvent_락경합_스킵` | `lockProvider.lock()` → empty | `eventRepository.findById()` 호출 안 됨 |
| `endEvent_성공` | 락 획득 + ACTIVE 이벤트 | `publishEvent(EventEndedEvent)` 호출 |
| `endEvent_이미종료된이벤트_스킵` | `event.isEnded()=true` | `publishEvent()` 호출 안 됨 |

---

## EventLifecycleListenerTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `ActiveEventCache`, `StringRedisTemplate` Mock

`@TransactionalEventListener(AFTER_COMMIT)` 메서드를 직접 호출해 후처리 동작을 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `onActivated_캐시_등록` | `EventActivatedEvent` 수신 | `activeEventCache.add()` 호출 |
| `onEnded_캐시제거_대기열삭제_PubSub발행` | `EventEndedEvent` 수신 | `cache.remove()` + `redis.delete()` + `redis.convertAndSend()` |

---

## EventRecoveryServiceTest

**방식:** `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(LENIENT)` — `EventRepository`, `EventLifecycleService`, `ActiveEventCache`, `TaskScheduler` Mock

`@LENIENT` 이유: `@BeforeEach`에서 `taskScheduler.schedule()` 스텁을 설정하지만 시나리오①처럼 스케줄러를 전혀 호출하지 않는 케이스가 있기 때문이다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `시나리오1_end_at_경과_즉시_종료처리` | overdue 1개 | `lifecycleService.endEvent(1L)` |
| `시나리오1_복수_overdue_모두_종료처리` | overdue 2개 | `endEvent(1L)`, `endEvent(2L)` |
| `시나리오2_start_at_경과_SCHEDULED_즉시_활성화_후_종료예약` | activatable 1개 | `activateEvent(2L)` + `schedule()` 1회 |
| `시나리오3_ACTIVE_캐시복원_종료예약` | ACTIVE + endAt 미래 | `cache.add(3L)` + `schedule()` 1회 |
| `시나리오3_ACTIVE이지만_end_at_경과_종료예약_안함` | ACTIVE + endAt 과거 | `cache.add(3L)` + `schedule()` 0회 |
| `시나리오4_SCHEDULED_start_at_미래_활성화_종료_모두예약` | SCHEDULED + startAt 미래 | `schedule()` 2회 |
| `시나리오4_SCHEDULED이지만_start_at_경과_필터링` | SCHEDULED + startAt 과거 | `schedule()` 0회 |
| `복구할_이벤트_없으면_아무것도_안함` | 전부 빈 목록 | 아무것도 호출 안 됨 |

---

## ActiveEventCacheTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `EventRepository` Mock, `ActiveEventCache` 직접 생성

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `동시_add_호출시_이벤트_유실없음` `@RepeatedTest(200)` | 2스레드 동시 add | `{1L, 2L}` 모두 보존 |
| `동시_remove_호출시_정확히_제거됨` `@RepeatedTest(200)` | 2스레드 동시 remove | 둘 다 제거됨 |
| `add_remove_정상동작` | 기본 CRUD | 순서대로 검증 |
| `refresh_DB_상태로_갱신` | DB mock → refresh | 캐시가 DB 결과로 교체 |
| `getAll_반환값은_불변` | `getAll()` 후 add 시도 | `UnsupportedOperationException` |

---

## Controller 슬라이스 테스트

**방식:** `@WebMvcTest` — MockMvc, 서비스/레포지터리 `@MockBean`

컨트롤러 테스트는 HTTP 계약만 검증한다. URL 라우팅, 요청/응답 직렬화, 예외→HTTP 상태 코드 변환이 대상이다. 비즈니스 로직은 서비스 Mock이 대신한다.

### ClaimControllerTest

| 테스트 | 핵심 검증 |
|--------|-----------|
| `claim_200` | HTTP 200, `success=true` |
| `claim_401_토큰없음` | HTTP 401 |
| `claim_409_재고소진` | HTTP 409 |

### EventControllerTest

| 테스트 | 핵심 검증 |
|--------|-----------|
| `이벤트_상태_200` | HTTP 200, `data.status: "ACTIVE"` |
| `이벤트_없으면_404` | HTTP 404 |

### QueueControllerTest

| 테스트 | 핵심 검증 |
|--------|-----------|
| `enter_200_rank_반환` | HTTP 200, `data.rank` |
| `enter_body없으면_400` | HTTP 400 |
| `status_대기중_isReady_false` | `data.rank` |
| `status_토큰발급완료_token_반환` | `data.token` |
| `status_파라미터_누락시_400` | HTTP 400 |

### SseControllerTest

| 테스트 | 핵심 검증 |
|--------|-----------|
| `subscribe_emitter_등록` | async 시작 + `emitterStore.put()` 호출 |

### ProductControllerTest

| 테스트 | 핵심 검증 |
|--------|-----------|
| `상품목록_200` | HTTP 200, `data[0].id`, `data[0].name`, `data[0].stock` |
| `상품없으면_빈배열_200` | HTTP 200, `data` 빈 배열 |

> `@MockBean`은 Spring Boot 3.4에서 deprecated됐다. 현재는 경고만 나고 동작에는 문제 없다.
