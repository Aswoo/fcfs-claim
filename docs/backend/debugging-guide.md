# 배포된 서버 디버깅 가이드

> 실제 버그를 찾아가는 과정을 바탕으로 작성한 주니어 개발자용 실전 가이드.

---

## 목차

1. [디버깅 기본 원칙](#1-디버깅-기본-원칙)
2. [로그 확인](#2-로그-확인)
3. [SQL 로깅 켜고 끄기](#3-sql-로깅-켜고-끄기)
4. [DB 직접 쿼리](#4-db-직접-쿼리)
5. [Redis 상태 확인](#5-redis-상태-확인)
6. [Pod 내부에서 API 직접 호출](#6-pod-내부에서-api-직접-호출)
7. [배포된 이미지 버전 확인](#7-배포된-이미지-버전-확인)
8. [실제 버그 케이스 스터디](#8-실제-버그-케이스-스터디)
9. [실무에서 이미지 태그는 어떻게 관리하나?](#9-실무에서-이미지-태그는-어떻게-관리하나)

---

## 1. 디버깅 기본 원칙

### 증상 → 원인을 좁혀가는 순서

```
증상 관찰
  → 영향 범위 파악 (프론트/백엔드/DB/캐시?)
    → 로그 확인
      → 상태 직접 조회 (DB, Redis, API)
        → 원인 특정
          → 수정 후 검증
```

**절대 하지 말 것:**
- 코드만 보고 "아마 이게 문제겠지"하고 추측으로 수정
- 수정 후 재배포만 하고 실제로 반영됐는지 확인 안 함
- 여러 곳을 동시에 수정 (어디서 고쳐진 건지 모름)

---

## 2. 로그 확인

### 기본 명령어

```bash
# 실시간 로그 스트리밍
kubectl logs -n fcfs -l app=fcfs-app -f

# 마지막 100줄
kubectl logs -n fcfs -l app=fcfs-app --tail=100

# 최근 5분치 로그
kubectl logs -n fcfs -l app=fcfs-app --since=5m

# 특정 Pod 지정
kubectl logs -n fcfs fcfs-app-xxxx-yyyy --tail=50
```

### 로그에서 원하는 것만 추출

```bash
# 에러/경고만
kubectl logs -n fcfs -l app=fcfs-app --tail=200 | grep -E "ERROR|WARN"

# 특정 키워드 (한글 포함)
kubectl logs -n fcfs -l app=fcfs-app --tail=200 | grep "이벤트\|queue"

# 노이즈 제거하고 핵심만
kubectl logs -n fcfs -l app=fcfs-app --tail=100 | \
  grep -v "INFO.*Spring\|INFO.*Hibernate\|DEBUG"
```

### 로그 레벨 이해

| 레벨 | 의미 | 언제 찍히나 |
|------|------|------------|
| ERROR | 처리 불가 에러 | 예외가 catch 안 됐을 때 |
| WARN | 주의 필요, 실행은 계속 | 예상치 못한 상황 |
| INFO | 주요 흐름 | 서비스 시작, 중요 이벤트 |
| DEBUG | 상세 실행 내역 | 개발 시에만 (운영엔 끔) |

> **주의**: 배포 서버에서 `"이벤트 복구 완료"` 직후에 예상한 로그가 없으면, 그 사이에 아무것도 실행되지 않은 것이다.

---

## 3. SQL 로깅 켜고 끄기

배포된 서버에서 JPA가 실제로 어떤 SQL을 날리는지 확인해야 할 때 사용한다.

### 켜는 방법

`application-docker.yml`:

```yaml
spring:
  jpa:
    show-sql: true          # SQL 출력
    properties:
      hibernate:
        format_sql: true    # SQL을 보기 좋게 포맷팅
```

Hibernate 6.x (Spring Boot 3.x)는 `show-sql: true`만으로는 부족할 수 있다. 아래 로거 레벨도 함께 설정:

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG                    # 실행된 SQL
    org.hibernate.orm.jdbc.bind: TRACE          # 바인딩된 파라미터 값
```

### 끄는 방법

```yaml
spring:
  jpa:
    show-sql: false
```

```yaml
logging:
  level:
    org.hibernate.SQL: WARN
    org.hibernate.orm.jdbc.bind: WARN
```

### SQL 로그 해석 예시

```
Hibernate: 
    update
        event 
    set
        status='ACTIVE',
        end_at=?      ← ? 는 바인딩 파라미터
    where
        id=?
```

`org.hibernate.orm.jdbc.bind: TRACE` 설정 시:
```
binding parameter [1] as [TIMESTAMP] - [2026-05-09T08:22:46]
binding parameter [2] as [BIGINT] - [1]
```

### SQL이 아예 안 찍힌다면?

SQL이 실행조차 안 되고 있다는 뜻이다.
→ **트랜잭션이 커밋되지 않았거나, 메서드 자체가 호출되지 않은 것**

이 경우 메서드 진입 여부를 확인하는 로그를 추가:

```java
@Transactional
public void reset() {
    log.info("reset() 진입");          // ← 이게 찍히는지 확인
    claimRepository.deleteAllInBatch();
    log.info("claim 삭제 완료");
    // ...
}
```

---

## 4. DB 직접 쿼리

서버가 DB를 어떻게 바꿨는지, 혹은 바뀌지 않았는지 직접 확인한다.

### MySQL Pod에 접속

```bash
# Pod 이름 찾기
kubectl get pods -n fcfs -l app=mysql

# MySQL 쿼리 실행
MYSQL_POD=$(kubectl get pods -n fcfs -l app=mysql -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n fcfs $MYSQL_POD -- mysql -uroot -ppassword fcfs_claim -e "SELECT * FROM event;"
```

### 이 프로젝트에서 자주 쓰는 쿼리

```sql
-- 이벤트 상태 확인
SELECT id, status, NOW(), end_at FROM event;

-- 대기열 토큰 현황
SELECT event_id, user_id, status, created_at FROM queue_token ORDER BY created_at DESC LIMIT 20;

-- 수령 내역
SELECT event_id, user_id, token, created_at FROM claim ORDER BY created_at DESC;

-- 재고 현황
SELECT id, name, stock, total_stock FROM product;
```

### 긴급 수정 (운영에서는 절대 하지 말 것)

개발/테스트 환경에서 DB 상태를 강제로 맞출 때만 사용:

```sql
-- 이벤트 강제 활성화 (테스트용)
UPDATE event SET status = 'ACTIVE', end_at = DATE_ADD(NOW(), INTERVAL 24 HOUR) WHERE id = 1;
```

> **운영 서버에서 직접 SQL UPDATE는 절대 금지.** 반드시 API를 통해서만.

---

## 5. Redis 상태 확인

Redis는 대기열, 토큰, ShedLock 등 실시간 상태를 담고 있다.

### Redis CLI 접속

```bash
REDIS_POD=$(kubectl get pods -n fcfs -l app=redis -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n fcfs $REDIS_POD -- redis-cli
```

### 이 프로젝트에서 확인할 키

```bash
# 모든 키 확인
kubectl exec -n fcfs $REDIS_POD -- redis-cli KEYS "*"

# 대기열 크기 확인
kubectl exec -n fcfs $REDIS_POD -- redis-cli ZCARD "queue:waiting:1"

# 대기열 전체 내용 (userId: score 순)
kubectl exec -n fcfs $REDIS_POD -- redis-cli ZRANGE "queue:waiting:1" 0 -1 WITHSCORES

# ShedLock 상태 (스케줄러 락)
kubectl exec -n fcfs $REDIS_POD -- redis-cli GET "job-lock:default:processQueue"
kubectl exec -n fcfs $REDIS_POD -- redis-cli TTL "job-lock:default:processQueue"

# 특정 유저의 토큰 확인
kubectl exec -n fcfs $REDIS_POD -- redis-cli GET "user:token:1:12345"
```

### Redis 키 패턴 해석

| 키 패턴 | 의미 |
|---------|------|
| `queue:waiting:{eventId}` | 대기 중인 유저들의 sorted set (score = 진입 시각) |
| `user:token:{eventId}:{userId}` | 유저에게 발급된 토큰 |
| `token:{eventId}:{token}` | 토큰 → userId 역매핑 |
| `job-lock:default:processQueue` | ShedLock 락 (있으면 스케줄러 실행 중) |

### 대기열이 줄지 않을 때 확인 순서

```bash
# 1. 대기열에 실제로 사람이 있는지
redis-cli ZCARD "queue:waiting:1"

# 2. 5초 기다렸다가 다시 확인 (줄고 있는지)
redis-cli ZCARD "queue:waiting:1"

# 3. ShedLock은 잡혀 있는지 (있어야 정상)
redis-cli TTL "job-lock:default:processQueue"
# → -2면 키가 없음 (스케줄러가 한번도 안 돌았거나 오래됨)
# → 1~2초면 정상 작동 중

# 4. 그래도 안 줄면 → activeEventCache가 비어있는 것
#    → DB에서 event 상태 확인 필요
```

---

## 6. Pod 내부에서 API 직접 호출

외부에서 API를 호출할 수 없을 때 (포트포워드 없이) Pod 안에서 직접 curl로 테스트.

```bash
BACKEND_POD=$(kubectl get pods -n fcfs -l app=fcfs-app \
  --field-selector=status.phase=Running \
  -o jsonpath='{.items[0].metadata.name}')

# GET 요청
kubectl exec -n fcfs $BACKEND_POD -- \
  curl -s http://localhost:8081/api/v1/queue/status?userId=1&eventId=1

# POST 요청
kubectl exec -n fcfs $BACKEND_POD -- \
  curl -s -X POST http://localhost:8081/api/v1/admin/reset

# POST with body
kubectl exec -n fcfs $BACKEND_POD -- \
  curl -s -X POST http://localhost:8081/api/v1/queue/enter \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"eventId":1}'
```

**이게 유용한 이유:**
- 포트포워드 없이 테스트 가능
- 외부 네트워크 문제를 배제하고 서버 자체만 테스트
- API 응답과 서버 로그를 동시에 볼 수 있음

---

## 7. 배포된 이미지 버전 확인

> "내가 수정한 코드가 서버에 올라갔는가?"를 반드시 확인해야 한다.

### 현재 Pod가 사용하는 이미지 확인

```bash
# Pod가 사용하는 이미지 SHA
kubectl get pod -n fcfs -l app=fcfs-app \
  -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'

# 로컬 Docker 이미지 SHA
docker inspect fcfs-claim-app:latest --format "{{.Id}}"
```

두 값이 다르면 → Pod는 구 버전 이미지를 사용 중.

### Jar 파일 생성 시각 확인

```bash
kubectl exec -n fcfs $BACKEND_POD -- ls -la /app/app.jar
```

빌드 시각과 Pod 안 jar 시각이 다르면 → 새 코드가 반영 안 된 것.

### `imagePullPolicy: IfNotPresent` 함정

이 설정은 "이미 있으면 새로 안 받는다"는 뜻이다.
`latest` 태그를 재사용하면 Kubernetes는 캐시된 구 이미지를 계속 쓴다.

**해결책: 매 빌드마다 유니크한 태그 사용**

```makefile
IMAGE_TAG := $(shell date +%Y%m%d%H%M%S)
IMAGE     := fcfs-claim-app:$(IMAGE_TAG)

redeploy:
    docker build -t $(IMAGE) -t fcfs-claim-app:latest ./backend
    echo "$(IMAGE_TAG)" > .image-tag
    kubectl set image deployment/fcfs-app fcfs-app=$(IMAGE) -n $(NAMESPACE)
    kubectl rollout status deployment/fcfs-app -n $(NAMESPACE) --timeout=90s
```

`kubectl rollout restart` 대신 `kubectl set image`를 써야 Kubernetes가 새 태그를 인식하고 새 이미지를 로드한다.

---

## 8. 실제 버그 케이스 스터디

이 프로젝트에서 실제로 발생했던 버그와 찾아가는 과정.

---

### 케이스 1: 대기열 진입은 되지만 처리가 안 됨

**증상**: WaitingScreen에서 순위가 21에서 줄어들지 않음.

**디버깅 과정:**

```
1. 대기열에 실제로 사람이 있는가?
   → redis-cli ZCARD "queue:waiting:1"
   → 42명 확인. Redis에는 분명히 있다.

2. 5초 후에도 42명인가?
   → 5초 후 다시 확인 → 여전히 42명.
   → processQueue가 처리를 안 하고 있다.

3. ShedLock은 정상인가?
   → redis-cli TTL "job-lock:default:processQueue" → 1
   → 스케줄러는 1초마다 돌고 있다. 문제없음.

4. 그럼 왜 처리 안 하나? 코드 확인
   → processQueue(): if (eventIds.isEmpty()) return;
   → activeEventCache가 비어있으면 즉시 리턴.

5. activeEventCache는 왜 비어있나?
   → DB에서 이벤트 상태 확인
   → SELECT status FROM event WHERE id=1; → ENDED

6. 이벤트가 ENDED인데 왜 enter()가 성공했나?
   → 대기열의 타임스탬프 확인 → 일부 유저는 이벤트 종료 전 진입
   → reset() 호출 후 activeEventCache.add()는 됐지만
      DB UPDATE가 안 되어서 재시작 시 다시 ENDED로 인식
```

**원인**: 디버깅 과정에서 SQL 로그에 아무 쿼리도 찍히지 않았다. 즉 DB UPDATE가 실행조차 안 됐다.
정확한 JPA 내부 원인은 특정하지 못했다. (`reset()`은 `@Transactional` read-write이고, 그 안의 `findAll()`도 외부 트랜잭션에 합류하므로 dirty checking이 정상 작동해야 한다.)

그런데 디버깅 중 훨씬 더 근본적인 문제가 발견됐다: **`make redeploy`가 항상 구 이미지를 사용하고 있었다.** (케이스 2 참고.) 이미지 문제를 해결하기 전에는 수정 코드 자체가 서버에 올라간 적이 없었으므로, "JPA dirty checking이 안 된다"는 것을 실제로 검증한 적이 없다.

결론적으로:
- JPA dirty checking 문제가 있었는지는 불확실
- **이미지 캐시 문제는 확실하게 존재했고, 이것이 모든 수정이 무의미해 보인 실제 원인**

**수정**: 확실한 동작을 위해 native SQL 쿼리로 교체 (dirty checking 의존 제거)

```java
// 수정 전: JPA dirty checking에 의존
eventRepository.findAll().forEach(event -> {
    event.resetForTest();
});

// 수정 후: native SQL로 직접 UPDATE (명시적, 확실)
eventRepository.reactivateAll(LocalDateTime.now().plusHours(24));
```

```java
@Modifying
@Query(value = "UPDATE event SET status = 'ACTIVE', end_at = :endAt", nativeQuery = true)
void reactivateAll(@Param("endAt") LocalDateTime endAt);
```

**교훈**:
- 수정이 안 먹히는 것 같을 때, **원인을 추측하기 전에 "수정 코드가 실제로 서버에 올라갔는가"를 먼저 확인하라.** (이미지 버전 확인)
- Bulk 업데이트(다수 row를 한 번에 변경)는 JPA dirty checking보다 `@Modifying` native query가 명시적이고 안전하다.

---

### 케이스 2: 코드 수정했는데 서버에서 반영이 안 됨

**증상**: `ResetService.java`를 수정하고 `make redeploy`를 했는데, 서버 로그에 내가 추가한 `log.info()`가 안 찍힘.

**디버깅 과정:**

```
1. Pod가 새로 뜬 게 맞는가?
   → kubectl get pods -n fcfs → 새 Pod 이름 확인

2. 그 Pod가 새 이미지를 쓰고 있는가?
   → kubectl get pod ... -o jsonpath='{...imageID}'
   → sha256:effc6af2dcd6...  ← 구 이미지

3. 로컬 latest 이미지 ID는?
   → docker inspect fcfs-claim-app:latest --format "{{.Id}}"
   → sha256:d4624fb02afb...  ← 새 이미지

4. 왜 다른가?
   → imagePullPolicy: IfNotPresent
   → Kubernetes containerd 캐시에 latest → 구 SHA가 매핑돼 있음
   → docker build로 latest를 새로 만들어도 containerd는 캐시된 구 이미지를 씀
   → kubectl rollout restart는 "같은 이미지 스펙으로 재시작"이라 변화 없음
```

**원인**: `imagePullPolicy: IfNotPresent` + `latest` 태그 재사용. Kubernetes containerd의 로컬 이미지 캐시가 업데이트되지 않음.

**이 상황에서 나타나는 증상들:**
- 코드를 수정하고 재배포했는데 SQL 로그에 쿼리가 안 찍힘
- `reset()` API를 호출해도 DB에 UPDATE가 된 흔적이 없음 (SELECT로 확인)
- `log.info()` 추가했는데 그 로그가 안 나옴

이 모든 증상의 공통 해석: **내가 수정한 코드가 서버에서 단 한 번도 실행된 적이 없다.**

DB에 기록이 없다 → 구 코드가 돌았다 → 구 코드에는 그 로직 자체가 없다.

**수정**: 매 빌드마다 타임스탬프 태그를 생성하고 `kubectl set image`로 강제 업데이트.

**교훈**:
- `latest` 태그는 "항상 최신"을 보장하지 않는다. 캐시가 있으면 캐시를 쓴다.
- 배포 후 **반드시 imageID를 확인**해서 실제로 새 이미지가 올라갔는지 검증하라.
- 코드 수정 → 재배포 → 변화 없음 → 먼저 이미지 버전부터 확인하라.

---

### 케이스 3: SSE 실패 시 프론트엔드가 무한 대기

**증상**: 대기열 진입 후 WaitingScreen이 Ready로 이동하지 않고 계속 21번째에 머묾.

**원인**: SSE(Server-Sent Events) 연결이 끊기면 `ready` 이벤트를 받지 못한다. 그런데 폴링 fallback 코드가 `isReady=true`인 경우를 처리하지 않았다.

```typescript
// 수정 전: rank가 0이면 업데이트 안 함 (isReady=true도 무시)
const poll = async () => {
  const status = await queueService.getStatus(userId, eventId);
  if (status.rank > 0) setRank(status.rank);  // rank=0이면 아무것도 안 함!
};

// 수정 후: isReady 체크 추가
const poll = async () => {
  const status = await queueService.getStatus(userId, eventId);
  if (status.isReady && status.token) {
    navigation.replace('Ready', { token: status.token, ... });
  } else if (status.rank > 0) {
    setRank(status.rank);
  }
};
```

**교훈**:
- SSE, WebSocket 등 실시간 채널은 언제든 끊길 수 있다.
- **항상 폴링 fallback을 만들고, fallback이 실제로 동작하는지 SSE를 끊어서 테스트하라.**
- `if (rank > 0)` 같은 단순 조건이 edge case를 놓치는 경우가 많다.

---

## 9. 실무에서 이미지 태그는 어떻게 관리하나?

### `imagePullPolicy: IfNotPresent`가 존재하는 이유

이 설정이 기본값인 데는 이유가 있다:

| 상황 | 이유 |
|------|------|
| **로컬/오프라인 환경** | 레지스트리 없이 직접 로드한 이미지 사용 (Minikube, Docker Desktop Kubernetes) |
| **대규모 클러스터** | 수십 개 Pod 재시작 시 레지스트리에서 매번 풀하면 네트워크 과부하 |
| **레지스트리 장애 대응** | 레지스트리가 일시 장애여도 캐시된 이미지로 Pod 재시작 가능 |

설정 자체는 문제가 아니다. **`latest` 태그처럼 내용이 바뀌는 태그와 함께 쓰는 것이 문제다.**

### 실무에서 쓰는 이미지 태그 전략

#### 1. Git 커밋 SHA (가장 일반적)

```bash
docker build -t myapp:$(git rev-parse --short HEAD) .
# → myapp:abc1234
```

- 어떤 커밋이 실행 중인지 즉시 추적 가능
- CI/CD 파이프라인(GitHub Actions, GitLab CI)에서 표준

#### 2. 시맨틱 버전 (릴리즈용)

```bash
docker build -t myapp:1.2.3 .
```

- 배포 단위가 명확한 경우 (모바일 앱 백엔드 등)

#### 3. 타임스탬프 (이 프로젝트 방식)

```bash
IMAGE_TAG=$(date +%Y%m%d%H%M%S)
docker build -t myapp:$IMAGE_TAG .
```

- 간단하고 충돌 없음
- 단점: 어떤 코드인지 SHA 없이는 추적 불가

### 핵심 규칙

```
고정 태그(latest, stable) + IfNotPresent = 위험
유니크 태그(SHA, 타임스탬프) + IfNotPresent = 안전
유니크 태그 + Always = 더 안전하지만 느림
```

`Always`는 "매번 레지스트리에서 풀"이라 외부 레지스트리(ECR, DockerHub) 없이 로컬 이미지만 쓰는 이 프로젝트 구조에서는 쓰기 어렵다.

### CI/CD 파이프라인에서의 흐름

실무 팀은 보통 이렇게 한다:

```
git push → CI 트리거
  → docker build -t myapp:$GIT_SHA
  → docker push myapp:$GIT_SHA (레지스트리로)
  → kubectl set image deployment/myapp myapp:$GIT_SHA
  → kubectl rollout status
```

"내가 배포한 게 맞는가?"는 `kubectl get pod -o jsonpath='...imageID'`로 확인하는 게 아니라, 파이프라인 로그에서 커밋 SHA를 보고 확인한다.

---

## 빠른 참고: 상황별 명령어

| 상황 | 명령어 |
|------|--------|
| 실시간 로그 보기 | `make logs` |
| Pod 상태 확인 | `make status` |
| 대기열 인원 수 | `redis-cli ZCARD "queue:waiting:1"` |
| 이벤트 DB 상태 | `SELECT status, end_at FROM event;` |
| 현재 이미지 확인 | `kubectl get pod ... -o jsonpath='...imageID'` |
| 새 이미지로 재배포 | `make redeploy` |
| reset API 직접 호출 | `kubectl exec ... -- curl -X POST http://localhost:8081/api/v1/admin/reset` |
