# 테스트 코드 작성 계획

## 전략

```
단위 테스트 (Mockito)      → 서비스 비즈니스 로직. Redis/DB 전부 Mock
Repository 테스트          → JPA 커스텀 쿼리. H2 실제 실행
Controller 슬라이스 테스트  → HTTP 레이어. MockMvc
```

통합 테스트(`@SpringBootTest`)는 Redis 실서버 필요 + 무거워서 제외.

---

## 파일 구조

```
src/test/java/com/example/fcfsclaim/
├── domain/
│   ├── claim/
│   │   ├── service/    ClaimServiceTest.java         ← 1순위
│   │   └── controller/ ClaimControllerTest.java      ← 4순위
│   ├── queue/
│   │   └── service/    QueueServiceTest.java         ← 2순위
│   ├── product/
│   │   └── repository/ ProductRepositoryTest.java    ← 3순위
│   └── event/
│       └── service/    EventLifecycleServiceTest.java ← 3순위
└── FcfsClaimApplicationTests.java  (기존 — 컨텍스트 로딩만)
```

---

## 1순위 — ClaimServiceTest

**Mock 대상:** `StringRedisTemplate`, `ClaimRepository`, `QueueTokenRepository`, `ProductRepository`

| 테스트 메서드 | 시나리오 | 검증 포인트 |
|---|---|---|
| `claim_성공()` | 유효 토큰 + 재고 있음 | `claimRepository.save()`, `decrementStock()` 호출, Redis 토큰 삭제 |
| `claim_토큰없음_401()` | Redis에 키 없음 | `ResponseStatusException(401)` |
| `claim_토큰소유자불일치_403()` | 토큰 userId ≠ 요청 userId | `ResponseStatusException(403)` |
| `claim_중복수령_409()` | `save()` → `DataIntegrityViolationException` | `ResponseStatusException(409, "이미 수령")` |
| `claim_재고소진_409()` | `decrementStock()` 반환 0 | `ResponseStatusException(409, "재고")` |
| `claim_재고소진시_Redis토큰_미삭제()` | 재고 소진 → 트랜잭션 롤백 | `redis.delete()` **호출 안 됨** |

> 마지막 케이스 핵심: 재고 소진 시 `@Transactional` 롤백으로 Redis 토큰 삭제가 일어나면 안 된다.

---

## 2순위 — QueueServiceTest

**Mock 대상:** `StringRedisTemplate`, `QueueTokenRepository`, `ObjectMapper`, `ActiveEventCache`

| 테스트 메서드 | 시나리오 | 검증 포인트 |
|---|---|---|
| `enter_신규입장_rank반환()` | 처음 입장 | `ZADD NX` 호출, rank 반환 |
| `enter_중복입장_기존rank반환()` | 이미 토큰 있는 userId | `userTokenKey` 존재 → rank 0 반환 |
| `getStatus_대기중()` | 토큰 없고 ZSET에 있음 | `isReady=false`, rank 반환 |
| `getStatus_토큰발급완료()` | `user:token:` 키 있음 | `StatusResponse.ready(token)` |
| `validateToken_유효()` | `token:` 키 있음 | `true` |
| `validateToken_만료()` | `token:` 키 없음 | `false` |

---

## 3순위 — ProductRepositoryTest

**방식:** `@DataJpaTest` — H2 실제 실행, JPA 레이어만 로드

| 테스트 메서드 | 시나리오 | 검증 포인트 |
|---|---|---|
| `decrementStock_재고있음()` | stock=3 차감 | `affectedRows=1`, stock=2 |
| `decrementStock_재고없음()` | stock=0 차감 시도 | `affectedRows=0`, stock=0 유지 |
| `decrementStock_동시100건_재고3개()` | `ExecutorService` 100스레드 동시 | 최종 stock=0, 성공 건수=3 |

> 세 번째 케이스가 핵심. `AND stock > 0` 조건이 동시성 상황에서도 정확히 지켜지는지 검증.

---

## 3순위 — EventLifecycleServiceTest

**Mock 대상:** `EventRepository`, `ActiveEventCache`, `StringRedisTemplate`, `SseEmitterStore`, `LockProvider`

| 테스트 메서드 | 시나리오 | 검증 포인트 |
|---|---|---|
| `activateEvent_성공()` | SCHEDULED 이벤트, 락 획득 | `event.activate()`, `cache.add()` 호출 |
| `activateEvent_락경합_스킵()` | `lockProvider.lock()` → empty | `doActivate()` 호출 안 됨 |
| `endEvent_성공()` | ACTIVE 이벤트, 락 획득 | `event.end()`, `cache.remove()`, Redis 대기열 삭제, Pub/Sub 발행 |
| `endEvent_이미종료된이벤트_스킵()` | `event.isEnded()=true` | 상태 변경 없음 |

---

## 4순위 — ClaimControllerTest

**방식:** `@WebMvcTest(ClaimController.class)` — MockMvc, `ClaimService` Mock

| 테스트 메서드 | 시나리오 | 검증 포인트 |
|---|---|---|
| `claim_200()` | 정상 요청 | HTTP 200, `success=true` |
| `claim_401_토큰없음()` | Service → 401 예외 | HTTP 401, 에러 메시지 포함 |
| `claim_409_재고소진()` | Service → 409 예외 | HTTP 409 |

---

## 작성 순서

```
1일차: ClaimServiceTest        (6개)  핵심 비즈니스 로직
2일차: ProductRepositoryTest   (3개)  동시성 쿼리
3일차: QueueServiceTest        (6개)
4일차: EventLifecycleServiceTest (4개) + ClaimControllerTest (3개)
```
