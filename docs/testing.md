# 테스트 코드 가이드

## 전략

```
단위 테스트 (Mockito)      → 서비스 비즈니스 로직. Redis/DB 전부 Mock
Repository 테스트          → JPA 커스텀 쿼리. H2 실제 실행
Controller 슬라이스 테스트  → HTTP 레이어. MockMvc
```

통합 테스트(`@SpringBootTest`)는 Redis 실서버가 필요하고 무거워서 제외했다.

---

## 파일 구조

```
src/test/java/com/example/fcfsclaim/
├── domain/
│   ├── claim/
│   │   ├── service/    ClaimServiceTest.java         ← 비즈니스 핵심
│   │   └── controller/ ClaimControllerTest.java      ← HTTP 레이어
│   ├── queue/
│   │   └── service/    QueueServiceTest.java
│   ├── product/
│   │   └── repository/ ProductRepositoryTest.java    ← 동시성 쿼리
│   └── event/
│       └── service/    EventLifecycleServiceTest.java
└── FcfsClaimApplicationTests.java  (컨텍스트 로딩만)
```

---

## 실행 방법

```bash
# 전체 테스트
./gradlew test

# 특정 클래스만
./gradlew test --tests "com.example.fcfsclaim.domain.claim.service.ClaimServiceTest"

# 테스트 HTML 리포트 열기
open backend/build/reports/tests/test/index.html
```

---

## ClaimServiceTest — 비즈니스 핵심

**방식:** `@ExtendWith(MockitoExtension.class)` — `StringRedisTemplate`, `ClaimRepository`, `ProductRepository`, `QueueTokenRepository` 모두 Mock

```
claim() 흐름:
  1. Redis에서 토큰 키 조회 → 없으면 401
  2. Redis의 userId 와 요청 userId 비교 → 불일치 시 403
  3. Claim DB 저장 → UNIQUE 제약 위반 시 409
  4. Product.stock 차감 → affected rows = 0 이면 409
  5. Redis 토큰 키 삭제, QueueToken.markUsed()
```

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `claim_성공` | 유효 토큰 + 재고 있음 | `save()`, `decrementStock()`, `redis.delete()` 호출 |
| `claim_토큰없음_401` | Redis 키 없음 | `UNAUTHORIZED` 예외 |
| `claim_토큰소유자불일치_403` | 다른 userId | `FORBIDDEN` 예외 |
| `claim_중복수령_409` | `save()` → `DataIntegrityViolationException` | `CONFLICT` + "이미 수령" 메시지 |
| `claim_재고소진_409` | `decrementStock()` → 0 반환 | `CONFLICT` + "재고" 메시지 |
| `claim_재고소진시_Redis토큰_미삭제` | 재고 소진 → 예외 | `redis.delete()` **호출 안 됨** |

마지막 케이스가 핵심이다. 재고가 소진되면 `ResponseStatusException`이 발생하고 `@Transactional`이 롤백되는데, 이때 Redis 토큰 삭제(`redis.delete()`)는 아직 실행 전이어야 한다. `verify(redis, never()).delete(TOKEN_KEY)`로 검증한다.

---

## QueueServiceTest

**방식:** `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(LENIENT)` — `StringRedisTemplate`, `QueueTokenRepository`, `ObjectMapper`, `ActiveEventCache` Mock

`@LENIENT` 이유: `@BeforeEach`에서 `opsForValue()`와 `opsForZSet()` 스텁을 공통으로 설정하는데, `validateToken()` 테스트처럼 둘 다 사용하지 않는 케이스가 있어서 Mockito 기본 strict 모드가 "불필요한 스텁" 에러를 냈기 때문이다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `enter_신규입장_rank반환` | 처음 입장 | `ZADD NX` 호출, rank 1 반환 |
| `enter_중복입장_기존rank반환` | `user:token:` 키 있음 | rank 0 반환 |
| `getStatus_대기중` | 토큰 없고 ZSET에 있음 | `isReady=false`, rank 반환 |
| `getStatus_토큰발급완료` | `user:token:` 키 있음 | `isReady=true`, 토큰 반환 |
| `validateToken_유효` | `token:` 키 있음 | `true` |
| `validateToken_만료` | `token:` 키 없음 | `false` |

---

## ProductRepositoryTest — 동시성 쿼리

**방식:** `@DataJpaTest` — H2 인메모리 DB 실제 실행, JPA 레이어만 로드

```java
// ProductRepository 핵심 쿼리
@Modifying(clearAutomatically = true)
@Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :productId AND p.stock > 0")
int decrementStock(Long productId);
```

`clearAutomatically = true`: `@Modifying` 쿼리는 JPA를 우회해 직접 SQL을 실행한다. 같은 트랜잭션 안에서 `findById()`로 조회하면 1차 캐시(EntityManager)에서 오래된 값을 반환할 수 있다. 이 플래그를 켜면 쿼리 실행 후 1차 캐시를 자동으로 비워서 이후 조회가 DB를 다시 읽는다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `decrementStock_재고있음` | stock=3 차감 | `affectedRows=1`, stock=2 |
| `decrementStock_재고없음` | stock=0 차감 시도 | `affectedRows=0`, stock=0 유지 |
| `decrementStock_동시100건_재고3개` | 100스레드 동시 | `stock=0`, `successCount=3` |

### 동시성 테스트 구현 포인트

```java
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void decrementStock_동시100건_재고3개() throws InterruptedException {
    TransactionTemplate txTemplate = new TransactionTemplate(txManager);
    // ...
}
```

`@Transactional(NOT_SUPPORTED)` 이유:
- `@DataJpaTest`는 기본적으로 테스트 메서드를 하나의 트랜잭션으로 감싸고 끝나면 롤백한다.
- 이 트랜잭션은 메인 스레드의 것이다. 100개의 스레드가 새로 생성될 때 트랜잭션을 공유하지 않는다.
- 다른 스레드는 커밋되지 않은 데이터(메인 트랜잭션 안의 `@BeforeEach` 데이터)를 볼 수 없다(READ_COMMITTED).
- `NOT_SUPPORTED`로 테스트 메서드의 트랜잭션 래핑을 끄면, `@BeforeEach`의 `save()`가 자체 트랜잭션으로 커밋되어 다른 스레드에서 보인다.

`TransactionTemplate` 이유:
- `@Modifying` 쿼리는 활성 트랜잭션이 없으면 `TransactionRequiredException`이 발생한다.
- 각 스레드가 `TransactionTemplate.execute()`로 독립적인 트랜잭션을 생성해야 한다.

---

## EventLifecycleServiceTest

**방식:** `@ExtendWith(MockitoExtension.class)` — `EventRepository`, `ActiveEventCache`, `StringRedisTemplate`, `SseEmitterStore`, `LockProvider` Mock

이 서비스는 ShedLock의 `LockProvider`를 직접 사용한다(`@SchedulerLock` 어노테이션 대신). 락 획득 성공/실패 두 경우 모두 테스트한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `activateEvent_성공` | SCHEDULED 이벤트, 락 획득 | `event.activate()`, `cache.add()` 호출 |
| `activateEvent_락경합_스킵` | `lockProvider.lock()` → `Optional.empty()` | `eventRepository.findById()` 호출 안 됨 |
| `endEvent_성공` | ACTIVE 이벤트, 락 획득 | `cache.remove()`, Redis 키 삭제, Pub/Sub 발행 |
| `endEvent_이미종료된이벤트_스킵` | `event.isEnded()=true` | `cache.remove()`, `redis.delete()` 호출 안 됨 |

---

## ClaimControllerTest — HTTP 레이어

**방식:** `@WebMvcTest(ClaimController.class)` — MockMvc, `ClaimService` `@MockBean`으로 대체

컨트롤러 자체 로직은 없고(`return ApiResponse.ok(claimService.claim(request))`), HTTP 상태 코드가 서비스 예외와 올바르게 매핑되는지를 검증한다.

| 테스트 | 시나리오 | 핵심 검증 |
|--------|----------|-----------|
| `claim_200` | 정상 | HTTP 200, `success=true` |
| `claim_401_토큰없음` | `UNAUTHORIZED` 예외 | HTTP 401 |
| `claim_409_재고소진` | `CONFLICT` 예외 | HTTP 409 |

> `@MockBean`은 Spring Boot 3.4에서 deprecated됐다. 현재는 경고만 나고 동작에는 문제 없다. 이후 `@WebMvcTest` + `@Import` 방식으로 전환할 수 있다.
