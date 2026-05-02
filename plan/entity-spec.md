# Entity Specification — FCFS-Claim System

> **이 문서의 목적**: Claude Code가 읽고 Spring Boot JPA 엔티티 클래스와 Repository를 생성하기 위한 단일 소스입니다.
>
> **기술 스택**: Java 17 / Spring Boot 3.x / Spring Data JPA / Hibernate / MySQL 8.x
>
> **패키지 루트**: `com.fcfsclaim`
>
> **DB 스키마 관리**: `ddl-auto: create` — 앱 시작 시 엔티티 기반으로 테이블 자동 생성

---

## 목차

1. [엔티티 목록](#1-엔티티-목록)
2. [공통 규칙](#2-공통-규칙)
3. [ItemEvent](#3-itemevent)
4. [ClaimHistory](#4-claimhistory)
5. [연관관계 요약](#5-연관관계-요약)
6. [Repository 인터페이스](#6-repository-인터페이스)
7. [생성 지시사항](#7-생성-지시사항)

---

## 1. 엔티티 목록

| 엔티티 클래스 | 테이블명 | 설명 |
|--------------|---------|------|
| `ItemEvent` | `item_events` | 선착순 지급 이벤트 (수량, 상태, 기간 관리) |
| `ClaimHistory` | `claim_history` | 사용자 수령 이력 (선착순 번호, 중복 방지) |

---

## 2. 공통 규칙

### 네이밍 컨벤션

```
Java 필드:  camelCase      예) totalQuantity
DB 컬럼:    snake_case     예) total_quantity
```

### 공통 Auditing 설정

모든 엔티티에 `@EntityListeners(AuditingEntityListener.class)` 적용.
`JpaConfig`에 `@EnableJpaAuditing` 활성화 필요.

```java
// com/example/fcfsclaim/config/JpaConfig.java
@Configuration
@EnableJpaAuditing
public class JpaConfig {}
```

### 공통 어노테이션 패턴

```java
@Entity
@Table(name = "테이블명")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 기본 생성자
// setter 없음 — 정적 팩토리 메서드로만 생성
```

### 기본 타입 매핑

| Java 타입 | DB 타입 | 비고 |
|-----------|---------|------|
| `Long` | `BIGINT` | PK, FK |
| `String` | `VARCHAR(N)` | N은 필드별 명시 |
| `int` | `INT` | 수량, 순번 |
| `LocalDateTime` | `DATETIME` | 타임존 없음 (서버 UTC 기준) |
| `Enum` | `VARCHAR(20)` | `@Enumerated(EnumType.STRING)` |

---

## 3. ItemEvent

### 클래스 정보

```
패키지:  com.fcfsclaim.domain.item.entity
파일:    ItemEvent.java
테이블:  item_events
```

### 필드 명세

| 필드명 | Java 타입 | DB 컬럼 | DB 타입 | 제약조건 | 설명 |
|--------|----------|---------|---------|---------|------|
| `id` | `Long` | `id` | `BIGINT` | PK, AUTO_INCREMENT, NOT NULL | 이벤트 식별자 |
| `name` | `String` | `name` | `VARCHAR(200)` | NOT NULL | 이벤트 이름 |
| `totalQuantity` | `int` | `total_quantity` | `INT` | NOT NULL | 전체 지급 수량 (예: 20) |
| `status` | `EventStatus` | `status` | `VARCHAR(20)` | NOT NULL | 이벤트 상태 (Enum) |
| `startAt` | `LocalDateTime` | `start_at` | `DATETIME` | NULLABLE | 이벤트 시작 일시 |
| `endAt` | `LocalDateTime` | `end_at` | `DATETIME` | NULLABLE | 이벤트 종료 일시 |
| `createdAt` | `LocalDateTime` | `created_at` | `DATETIME` | NOT NULL, 자동 생성 | `@CreatedDate` |
| `updatedAt` | `LocalDateTime` | `updated_at` | `DATETIME` | NOT NULL, 자동 갱신 | `@LastModifiedDate` |

### Enum: EventStatus

| 값 | 설명 | 전이 가능 상태 |
|----|------|--------------|
| `READY` | 생성됨, 아직 시작 안 됨 | → ACTIVE |
| `ACTIVE` | 진행 중, 신청 가능 | → CLOSED |
| `CLOSED` | 종료됨, 신청 불가 | — (최종 상태) |

### 인덱스

| 인덱스명 | 컬럼 | 용도 |
|---------|------|------|
| `idx_status` | `status` | 활성 이벤트 조회 필터링 |

### 비즈니스 메서드

| 메서드 | 동작 |
|--------|------|
| `activate()` | READY → ACTIVE. READY가 아니면 예외 |
| `close()` | ACTIVE → CLOSED. ACTIVE가 아니면 예외 |
| `isActive()` | status == ACTIVE 여부 반환 |

### 정적 팩토리 메서드

```
ItemEvent.create(String name, int totalQuantity, LocalDateTime startAt, LocalDateTime endAt)
  → status = READY 로 초기화하여 반환
```

### 완성 코드

```java
@Entity
@Table(
    name = "item_events",
    indexes = @Index(name = "idx_status", columnList = "status")
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private int totalQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static ItemEvent create(String name, int totalQuantity,
                                   LocalDateTime startAt, LocalDateTime endAt) {
        ItemEvent event = new ItemEvent();
        event.name = name;
        event.totalQuantity = totalQuantity;
        event.status = EventStatus.READY;
        event.startAt = startAt;
        event.endAt = endAt;
        return event;
    }

    public void activate() {
        if (this.status != EventStatus.READY) {
            throw new IllegalStateException("READY 상태에서만 활성화 가능합니다.");
        }
        this.status = EventStatus.ACTIVE;
    }

    public void close() {
        if (this.status != EventStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE 상태에서만 종료 가능합니다.");
        }
        this.status = EventStatus.CLOSED;
    }

    public boolean isActive() {
        return this.status == EventStatus.ACTIVE;
    }

    public enum EventStatus {
        READY, ACTIVE, CLOSED
    }
}
```

---

## 4. ClaimHistory

### 클래스 정보

```
패키지:  com.fcfsclaim.domain.item.entity
파일:    ClaimHistory.java
테이블:  claim_history
```

### 필드 명세

| 필드명 | Java 타입 | DB 컬럼 | DB 타입 | 제약조건 | 설명 |
|--------|----------|---------|---------|---------|------|
| `id` | `Long` | `id` | `BIGINT` | PK, AUTO_INCREMENT, NOT NULL | 수령 이력 식별자 |
| `eventId` | `Long` | `event_id` | `BIGINT` | NOT NULL | 이벤트 식별자 (논리 참조) |
| `userId` | `Long` | `user_id` | `BIGINT` | NOT NULL | 수령한 사용자 ID |
| `sequenceNumber` | `int` | `sequence_number` | `INT` | NOT NULL | 선착순 번호 (1 ~ totalQuantity) |
| `claimedAt` | `LocalDateTime` | `claimed_at` | `DATETIME` | NOT NULL, 자동 생성 | `@CreatedDate` |

### 제약조건

| 제약조건명 | 컬럼 조합 | 목적 |
|-----------|----------|------|
| `uk_event_user` | `(event_id, user_id)` | 중복 수령 2차 방어 — Redis 실패 시 DB 레벨 최종 차단 |

### 인덱스

| 인덱스명 | 컬럼 | 용도 |
|---------|------|------|
| `idx_claim_event_id` | `event_id` | 이벤트별 수령 목록 조회 |
| `idx_claim_user_id` | `user_id` | 사용자별 수령 이력 조회 |

### 물리 FK 미사용 이유

```
부하 테스트 환경에서 FK 제약조건이 대량 INSERT 성능에 영향을 줍니다.
eventId는 애플리케이션 레벨에서 유효성 검증 후 저장합니다.
```

### 정적 팩토리 메서드

```
ClaimHistory.of(Long eventId, Long userId, int sequenceNumber)
  → claimedAt 은 @CreatedDate 로 자동 주입
```

### 완성 코드

```java
@Entity
@Table(
    name = "claim_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_event_user",
        columnNames = {"event_id", "user_id"}
    ),
    indexes = {
        @Index(name = "idx_claim_event_id", columnList = "event_id"),
        @Index(name = "idx_claim_user_id",  columnList = "user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int sequenceNumber;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime claimedAt;

    public static ClaimHistory of(Long eventId, Long userId, int sequenceNumber) {
        ClaimHistory history = new ClaimHistory();
        history.eventId = eventId;
        history.userId = userId;
        history.sequenceNumber = sequenceNumber;
        return history;
    }
}
```

---

## 5. 연관관계 요약

```
ItemEvent (1) ────────── (N) ClaimHistory
               eventId (Long 직접 참조, @ManyToOne 없음)
```

### @ManyToOne 매핑을 하지 않는 이유

```
부하 테스트 시 대량 INSERT에서 JPA 연관관계가 예상치 못한 쿼리를 유발할 수 있습니다.
ClaimHistory는 단순 이력 저장 목적이므로 ItemEvent 객체 참조가 불필요합니다.
eventId (Long) 직접 보유로 Repository 레벨에서 쿼리 제어가 명확해집니다.
```

---

## 6. Repository 인터페이스

### ItemEventRepository

```java
// com/example/fcfsclaim/domain/item/repository/ItemEventRepository.java

public interface ItemEventRepository extends JpaRepository<ItemEvent, Long> {

    // 활성 이벤트 단건 조회
    Optional<ItemEvent> findByIdAndStatus(Long id, ItemEvent.EventStatus status);

    // 진행 중인 이벤트 목록
    List<ItemEvent> findAllByStatus(ItemEvent.EventStatus status);
}
```

### ClaimHistoryRepository

```java
// com/example/fcfsclaim/domain/item/repository/ClaimHistoryRepository.java

public interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {

    // 이벤트 수령 건수 (부하 테스트 결과 검증용)
    long countByEventId(Long eventId);

    // 이벤트별 전체 수령 이력
    List<ClaimHistory> findAllByEventId(Long eventId);

    // 특정 사용자의 수령 여부 확인
    boolean existsByEventIdAndUserId(Long eventId, Long userId);

    // 부하 테스트 리셋 시 이력 전체 삭제
    @Modifying
    @Transactional
    @Query("DELETE FROM ClaimHistory c WHERE c.eventId = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);
}
```

---

## 7. 생성 지시사항

> Claude Code는 이 섹션을 읽고 아래 순서대로 파일을 생성하세요.

### 생성 순서

```
1. src/main/java/com/example/fcfsclaim/config/JpaConfig.java
2. src/main/java/com/example/fcfsclaim/domain/item/entity/ItemEvent.java
3. src/main/java/com/example/fcfsclaim/domain/item/entity/ClaimHistory.java
4. src/main/java/com/example/fcfsclaim/domain/item/repository/ItemEventRepository.java
5. src/main/java/com/example/fcfsclaim/domain/item/repository/ClaimHistoryRepository.java
```

### application.yml DB 설정

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fcfs_claim?useSSL=false&serverTimezone=UTC
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: create        # 앱 시작 시 엔티티 보고 테이블 자동 생성
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
        format_sql: true
    open-in-view: false
```

### build.gradle 의존성

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly    'com.mysql:mysql-connector-j'
    compileOnly    'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

### 검증 체크리스트

```
□ JpaConfig에 @EnableJpaAuditing 없으면 createdAt / updatedAt null 오류 발생
□ ClaimHistory — uk_event_user UniqueConstraint 반드시 포함
□ EventStatus — @Enumerated(EnumType.STRING) 필수 (ORDINAL 사용 금지)
□ ClaimHistory — @ManyToOne 없음, eventId Long 직접 참조
□ 두 엔티티 모두 기본 생성자 protected — new 직접 생성 방지
□ setter 없음 — ItemEvent는 activate()/close(), ClaimHistory는 of()만 사용
```
