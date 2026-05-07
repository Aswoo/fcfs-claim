# 도메인 모델

이 프로젝트를 처음 보는 사람을 위한 엔티티 설명서. 실제 코드 기준으로 작성됐다.

---

## 패키지 구조

```
com.example.fcfsclaim.domain/
├── event/
│   ├── entity/   Event.java, EventStatus.java
│   ├── repository/   EventRepository.java
│   ├── controller/   EventController.java
│   ├── dto/          EventStatusResponse.java
│   └── service/      ActiveEventCache.java, EventLifecycleService.java, EventRecoveryService.java
├── product/
│   ├── entity/   Product.java
│   ├── repository/   ProductRepository.java
│   ├── controller/   ProductController.java
│   ├── dto/          ProductResponse.java
│   └── service/      ProductService.java
├── queue/
│   ├── entity/   QueueToken.java, TokenStatus.java
│   ├── repository/   QueueTokenRepository.java
│   ├── controller/   QueueController.java, SseController.java
│   ├── dto/          EnterRequest.java, EnterResponse.java, StatusResponse.java
│   └── service/      QueueService.java, SseEmitterStore.java, TokenExpiryService.java
│                     QueueReadyMessage.java, QueueReadySubscriber.java, QueueEndedSubscriber.java
├── claim/
│   ├── entity/   Claim.java
│   ├── repository/   ClaimRepository.java
│   ├── controller/   ClaimController.java
│   ├── dto/          ClaimRequest.java, ClaimResponse.java
│   └── service/      ClaimService.java
└── admin/
    ├── controller/   ResetController.java
    └── service/      ResetService.java
```

---

## 엔티티 상세

### Event

선착순 지급 행사 단위. 상태 머신으로 생명주기를 관리한다.

```java
@Entity @Table(name = "event")
public class Event {
    private Long id;
    private String name;
    private LocalDateTime startAt;   // 이벤트 시작 시각
    private LocalDateTime endAt;     // 이벤트 종료 시각

    @Enumerated(EnumType.STRING)
    private EventStatus status;      // SCHEDULED / ACTIVE / ENDED

    private LocalDateTime createdAt;
}
```

**팩토리 메서드**
```java
Event.of(String name, LocalDateTime startAt, LocalDateTime endAt)
// → status = SCHEDULED 으로 초기화
```

**상태 전이 메서드**
```java
event.activate()              // SCHEDULED → ACTIVE
event.end()                   // ACTIVE → ENDED
boolean event.isEnded()       // status == ENDED 여부
event.rescheduleToScheduled() // 테스트용: ENDED → SCHEDULED 리셋
```

**상태 머신**
```
SCHEDULED ──activate()──▶ ACTIVE ──end()──▶ ENDED
  (start_at 전)           (진행 중)           (종료)
```

---

### EventStatus (enum)

```java
public enum EventStatus {
    SCHEDULED,  // 생성됨, start_at 이전
    ACTIVE,     // 진행 중, 대기열 처리 중
    ENDED       // 종료, 대기열 정리됨
}
```

---

### Product

이벤트에 속한 개별 상품. 재고를 독립 관리한다.

```java
@Entity @Table(name = "product")
public class Product {
    private Long id;
    private Long eventId;       // FK (Long 직접 참조, @ManyToOne 없음)
    private String name;
    private String description;
    private int stock;          // 현재 재고 (claim 성공 시 -1)
    private int totalStock;     // 초기 재고 (불변, 소진율 계산용)
    private LocalDateTime createdAt;
}
```

**팩토리 메서드**
```java
Product.of(Long eventId, String name, String description, int stock)
// → totalStock = stock 으로 초기화 (두 값이 같은 상태에서 시작)
```

**재고 차감 방식**

엔티티 메서드가 아닌 Repository의 조건부 UPDATE로 처리한다. 동시성 안전을 위해 JPA dirty checking 대신 직접 SQL을 사용한다.

```java
// ProductRepository
@Modifying
@Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :id AND p.stock > 0")
int decrementStock(@Param("id") Long id);
// → affected rows = 0 이면 재고 소진 → ClaimService에서 409 반환
```

---

### QueueToken

대기열을 통과한 유저에게 발급되는 입장 토큰의 DB 이력.

```java
@Entity
@Table(name = "queue_token",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"}))
public class QueueToken {
    private Long id;
    private Long eventId;
    private Long userId;
    private String token;        // UUID (36자)
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;  // issuedAt + 300초

    @Enumerated(EnumType.STRING)
    private TokenStatus status;  // VALID / USED / EXPIRED
}
```

**팩토리 메서드**
```java
QueueToken.of(Long eventId, Long userId, String token)
// → expiresAt = issuedAt + 300초, status = VALID
```

**상태 전이 메서드**
```java
token.markUsed()  // claim 성공 시 VALID → USED
```

**EXPIRED 전이:** `TokenExpiryService`가 60초마다 `expires_at < now` AND `status = VALID` 인 레코드를 EXPIRED로 일괄 업데이트.

> **Redis와의 관계:** 실제 토큰 유효성 검증은 Redis `token:{eventId}:{token}` 키로 한다. DB는 영구 이력과 만료 배치 처리 용도로만 사용한다.

---

### TokenStatus (enum)

```java
public enum TokenStatus {
    VALID,    // 발급됨, 아직 미사용
    USED,     // claim 완료로 소진
    EXPIRED   // TTL 만료 (TokenExpiryService 배치 처리)
}
```

---

### Claim

실제 상품 수령 완료 기록. 이 row가 생기는 순간 수령이 확정된다.

```java
@Entity
@Table(name = "claim",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"}))
public class Claim {
    private Long id;
    private Long eventId;
    private Long userId;
    private Long productId;    // FK (Long 직접 참조)
    private String token;      // 사용된 토큰 (감사 추적용)
    private LocalDateTime claimedAt;
}
```

**팩토리 메서드**
```java
Claim.of(Long eventId, Long userId, Long productId, String token)
```

**UNIQUE 제약 `(event_id, user_id)`:** 중복 수령의 최종 방어선. `ClaimService`가 `save()` 시 `DataIntegrityViolationException`을 catch해 409 반환한다.

---

## 엔티티 공통 설계 원칙

### setter 없음, 팩토리 메서드로만 생성

```java
// ✗ 안 됨
Claim claim = new Claim();
claim.setUserId(123L);

// ✓ 이렇게 사용
Claim claim = Claim.of(eventId, userId, productId, token);
```

`@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 직접 생성을 차단하고, 정적 팩토리 메서드가 유일한 생성 경로다. 생성 시 설정해야 할 값이 명확해지고 불완전한 상태의 객체가 만들어지지 않는다.

### @ManyToOne 미사용

모든 FK는 `Long` 타입으로 직접 저장한다. 예를 들어 `claim.productId`는 `@ManyToOne Product product`가 아니라 `Long productId`다.

JPA 연관관계를 쓰면 지연 로딩 초기화, N+1 쿼리 등 암묵적인 DB 조회가 발생할 수 있다. 이 시스템은 부하 테스트가 목적이므로 쿼리를 명시적으로 제어하는 것을 선택했다.

### EnumType.STRING 강제

```java
@Enumerated(EnumType.STRING)  // ✓ "ACTIVE" 문자열로 저장
// EnumType.ORDINAL 사용 금지 — enum 순서 변경 시 DB 데이터 깨짐
```

---

## 도메인 간 흐름

```
[사용자 입장]
  QueueService.enter()
    → Redis ZADD queue:waiting:{eventId} (NX)
    → rank 반환

[스케줄러 — 1초마다]
  QueueService.processQueue()
    → activeEventCache.getAll() 로 ACTIVE 이벤트 확인 (DB 조회 없음)
    → Redis ZPOPMIN 으로 대기열에서 10명 추출
    → QueueToken 생성 → Redis 토큰 키 저장 → DB 저장
    → Pub/Sub "queue:ready" 발행

[SSE 알림]
  QueueReadySubscriber (모든 인스턴스)
    → SseEmitterStore 에서 userId 찾아 SSE 전송

[수령]
  ClaimService.claim()
    → Redis 토큰 유효성 검증
    → Claim DB 저장 (UNIQUE 제약으로 중복 방지)
    → Product.stock -1 (조건부 UPDATE)
    → Redis 토큰 키 삭제, QueueToken.markUsed()

[이벤트 종료]
  EventLifecycleService.endEvent()
    → Event.end()
    → activeEventCache.remove()
    → Redis queue:waiting 삭제
    → Pub/Sub "queue:ended" 발행
    → QueueEndedSubscriber → SSE "ended" 이벤트 전송
```
