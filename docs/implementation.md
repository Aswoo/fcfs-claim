# FCFS Claim — 구현 정리

선착순 한정 수량 지급 시스템 (스타벅스 프리퀀시 리워드 클론). 부하 테스트를 주목적으로 하며 별도 회원가입 없이 임의 userId로 동작한다.

---

## 전체 흐름

```
[사용자]
   │
   ▼
EnterScreen  ──POST /api/v1/queue/enter──▶  QueueService.enter()
   │                                             │ 순번 발급 (AtomicLong)
   ▼                                             │
WaitingScreen ──GET /api/v1/queue/status──▶  QueueService.getStatus()
   │  (2초 폴링)                                │ @Scheduled 1초마다 10명 처리
   │  isReady = true 되면 자동 이동              │ → UUID 토큰 발급
   ▼                                             │
ReadyScreen  (5분 카운트다운)
   │
   ▼
ClaimScreen  (상품 선택)
```

---

## 백엔드

### 기술 스택

| 항목 | 내용 |
|------|------|
| Framework | Spring Boot 3.5.0 |
| Language | Java 17 |
| Build | Gradle 8.x |
| DB (기본) | H2 in-memory (로컬 개발) |
| DB (운영) | MySQL 8.0 (Docker) |
| 인프라 | Docker, Nginx |

---

### 프로젝트 구조

```
backend/src/main/java/com/example/fcfsclaim/
├── FcfsClaimApplication.java          # @EnableScheduling 포함
├── common/
│   └── response/
│       └── ApiResponse.java           # 공통 응답 래퍼 { success, data, error }
└── domain/
    └── queue/
        ├── controller/
        │   └── QueueController.java
        ├── service/
        │   └── QueueService.java
        └── dto/
            ├── EnterRequest.java      # record(Long userId, Long eventId)
            ├── EnterResponse.java     # record(long rank)
            └── StatusResponse.java   # record(boolean isReady, long rank, String token)
```

---

### API 명세

#### `POST /api/v1/queue/enter`

대기열 진입. 동일 userId로 중복 호출해도 기존 순번을 재사용한다.

**Request Body**
```json
{ "userId": 123456, "eventId": 1 }
```

**Response**
```json
{
  "success": true,
  "data": { "rank": 42 },
  "error": null
}
```

---

#### `GET /api/v1/queue/status?userId=123456&eventId=1`

폴링용. 아직 대기 중이면 현재 순위를 반환하고, 입장 가능하면 토큰을 반환한다.

**Response (대기 중)**
```json
{
  "success": true,
  "data": { "isReady": false, "rank": 15, "token": null },
  "error": null
}
```

**Response (입장 가능)**
```json
{
  "success": true,
  "data": { "isReady": true, "rank": 0, "token": "550e8400-e29b-..." },
  "error": null
}
```

---

### 핵심: QueueService 인메모리 큐

```java
// userId → 부여된 순번
ConcurrentHashMap<Long, Long> userSequence

// userId → 발급된 토큰
ConcurrentHashMap<Long, String> userToken

// token → userId (검증용)
ConcurrentHashMap<String, Long> tokenStore

AtomicLong sequenceCounter   // 순번 카운터 (1씩 증가)
AtomicLong processingCursor  // 처리된 마지막 순번 (1초마다 +10)
```

**enter()** — userId를 처음 보면 순번을 발급. 재진입이면 기존 순번 반환.

```java
Long seq = userSequence.computeIfAbsent(userId, id -> sequenceCounter.incrementAndGet());
long rank = Math.max(1, seq - processingCursor.get());
```

**processQueue()** — 1초마다 10명씩 처리하고 토큰을 발급.

```java
@Scheduled(fixedDelay = 1000)
public void processQueue() {
    long maxSeq = sequenceCounter.get();
    long currentCursor = processingCursor.get();

    // 실제로 대기 중인 인원이 없으면 cursor를 앞서 올리지 않는다
    if (currentCursor >= maxSeq) return;

    long newCursor = Math.min(maxSeq, currentCursor + PROCESS_PER_SECOND);
    processingCursor.compareAndSet(currentCursor, newCursor);

    userSequence.forEach((userId, seq) -> {
        if (seq <= newCursor && !userToken.containsKey(userId)) {
            String token = UUID.randomUUID().toString();
            userToken.put(userId, token);
            tokenStore.put(token, userId);
        }
    });
}
```

**버그 수정 이력**: 초기 구현은 `processingCursor.addAndGet(10)`을 무조건 실행해서, 서버가 켜진 시간만큼 cursor가 미리 올라가 있었다. 예를 들어 서버 기동 후 30초가 지난 상태면 cursor=300인데, 21번 유저가 들어오면 `seq(21) <= cursor(300)`이 되어 즉시 토큰이 발급됐다. `sequenceCounter.get()`으로 실제 입장한 최대 순번을 cap으로 걸어서 cursor가 대기열을 앞서지 못하도록 수정했다.

```
// 수정 전: cursor가 서버 시작부터 계속 증가
cursor = 300 (30초 경과), seq = 21 → 즉시 토큰 발급

// 수정 후: cursor가 실제 입장 인원을 넘지 않음
t=1s: cursor = min(21, 0+10) = 10  → 1~10번 토큰
t=2s: cursor = min(21, 10+10) = 20 → 11~20번 토큰
t=3s: cursor = min(21, 20+10) = 21 → 21번(나) 토큰
```

> **주의**: 이 인메모리 큐는 서버를 재시작하거나 app1/app2로 로드밸런싱하면 상태가 공유되지 않는다. 다중 인스턴스 환경에서는 Redis로 교체 필요 (현재 미구현).

---

### Spring Profile 구성

| Profile | DB | 실행 방법 |
|---------|-----|----------|
| default | H2 in-memory | `./gradlew bootRun` |
| local | MySQL (로컬) | `./gradlew bootRun --args='--spring.profiles.active=local'` |
| docker | MySQL (컨테이너) | Docker Compose 환경에서 자동 적용 |

`application-local.yml` — MySQL 로컬 연결:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fcfs_claim?useSSL=false&serverTimezone=UTC
    username: root
    password: password
  jpa:
    hibernate:
      ddl-auto: update
server:
  port: 8081
```

---

### Docker 구성

#### `docker-compose.dev.yml` (개발용 — MySQL만)

```bash
docker compose -f docker-compose.dev.yml up -d
```

MySQL 컨테이너만 띄우고 Spring Boot는 로컬에서 `local` 프로필로 직접 실행.

#### `docker-compose.yml` (전체 스택)

```
nginx (포트 80)
  ├─▶ app1:8081  (Spring Boot)
  └─▶ app2:8081  (Spring Boot)
        ▼
      mysql:3306
      redis:6379  ← 준비만 된 상태, 아직 코드에서 미사용
```

#### `backend/Dockerfile` (멀티 스테이지 빌드)

```dockerfile
# Stage 1: 빌드 (JDK)
FROM eclipse-temurin:17-jdk-alpine AS build
RUN ./gradlew bootJar --no-daemon

# Stage 2: 실행 (JRE만 포함, 이미지 경량화)
FROM eclipse-temurin:17-jre
COPY --from=build /app/build/libs/*.jar app.jar
```

---

### Nginx Rate Limiting

`nginx/nginx.conf`:

```nginx
# 대기열 진입: IP당 초당 5회, burst 5
limit_req_zone $binary_remote_addr zone=queue_zone:10m rate=5r/s;

# 일반 API: IP당 초당 30회, burst 20
limit_req_zone $binary_remote_addr zone=api_zone:10m rate=30r/s;

upstream backend {
    server app1:8081;
    server app2:8081;  # round-robin (기본)
}
```

초과 시 HTTP 429 반환. `/api/v1/queue/enter`는 가장 엄격한 zone 적용.

---

## 프론트엔드

### 기술 스택

| 항목 | 내용 |
|------|------|
| Framework | React Native (Expo) |
| Language | TypeScript |
| Navigation | React Navigation v7 (Native Stack) |
| HTTP | Axios |
| 실행 포트 | 8082 (Expo Metro) |

---

### 화면 구성 (네비게이션 흐름)

```
Enter → Waiting → Ready → Claim
```

`AppNavigator.tsx`의 스택 파라미터 타입:
```typescript
type RootStackParamList = {
  Enter: undefined;
  Waiting: { rank: number; eventId: number; userId: number };
  Ready: { token: string; sequenceNumber: number };
  Claim: undefined;
};
```

---

### 화면별 역할

#### EnterScreen

- 이벤트 소개 + 대기열 진입 버튼
- 버튼 클릭 시 `queueService.enter()` 호출 → rank를 받아 WaitingScreen으로 이동
- `userId`는 `Math.floor(Math.random() * 900000) + 100000`으로 임의 생성 (부하 테스트용)
- `EVENT_ID = 1` 고정
- **"내 앞에 20명 먼저 입장" 버튼**: 랜덤 userId 20개를 `Promise.all`로 동시에 서버에 쏜 뒤 내 userId로 입장. 대기열 대기 흐름을 테스트할 때 사용한다.

```typescript
// 일반 입장
const { rank } = await queueService.enter(USER_ID, EVENT_ID);
navigation.navigate('Waiting', { rank, eventId: EVENT_ID, userId: USER_ID });

// 20명 선입장 후 입장
await queueService.simulatePrecedingUsers(20, EVENT_ID);
const { rank } = await queueService.enter(USER_ID, EVENT_ID);
navigation.navigate('Waiting', { rank, eventId: EVENT_ID, userId: USER_ID });
```

#### WaitingScreen

- 현재 순위 표시 (대형 숫자)
- 2초마다 `queueService.getStatus()` 폴링
- `isReady: true`가 되면 `navigation.replace('Ready', { token, sequenceNumber: rank })`로 자동 이동
- 애니메이션: 진행 바 (Animated.timing), 점 깜빡임 (Animated.loop)

```typescript
useEffect(() => {
  const poll = async () => {
    const status = await queueService.getStatus(userId, eventId);
    if (status.isReady && status.token) {
      navigation.replace('Ready', { token: status.token, sequenceNumber: rank });
      return;
    }
    setRank(status.rank);
  };
  const interval = setInterval(poll, 2000);
  return () => clearInterval(interval);
}, [userId, eventId, navigation]);
```

#### ReadyScreen

- 입장 가능 알림 + 5분 카운트다운 (`setInterval` 1초마다 -1)
- 카운트다운이 0이 되면 버튼 비활성화
- 버튼 클릭 → ClaimScreen으로 이동

#### ClaimScreen

- 상품 선택 화면 (UI 구현 완료, 백엔드 claim API 미연동)

---

### API 서비스 레이어

**`src/services/api.ts`** — axios 인스턴스

```typescript
const BASE_URL = 'http://192.168.0.8:8081';  // Mac 로컬 IP (Expo Go용)

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});
```

> `localhost`는 Expo Go에서 기기의 자기 자신을 가리키므로 Mac의 실제 IP(192.168.x.x)를 사용해야 한다.

**`src/services/queueService.ts`**

```typescript
enter: async (userId: number, eventId: number) => {
  const res = await api.post('/api/v1/queue/enter', { userId, eventId });
  return res.data.data as { rank: number };
},

getStatus: async (userId: number, eventId: number) => {
  const res = await api.get('/api/v1/queue/status', { params: { userId, eventId } });
  return res.data.data as { isReady: boolean; rank: number; token: string | null };
},

// 테스트용: count명의 가상 유저를 병렬로 먼저 입장시킨다
simulatePrecedingUsers: async (count: number, eventId: number) => {
  const requests = Array.from({ length: count }, () => {
    const fakeUserId = Math.floor(Math.random() * 9000000) + 1000000;
    return api.post('/api/v1/queue/enter', { userId: fakeUserId, eventId });
  });
  await Promise.all(requests);
},
```

---

### 개발 실행 방법

```bash
# 1. MySQL 컨테이너 시작 (docker-compose.dev.yml)
cd /Users/sdu/fcfs-claim
docker compose -f docker-compose.dev.yml up -d

# 2. Spring Boot 시작 (로컬 MySQL 연결)
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. Expo Metro 시작 (새 터미널)
cd frontend
npx expo start --port 8082
# → 물리 기기에서 Expo Go 앱으로 QR 코드 스캔
```

---

## 미구현 / 다음 단계

| 항목 | 현재 상태 | 비고 |
|------|----------|------|
| Claim API | 미구현 | `POST /api/v1/claim` 필요 |
| Redis 큐 | 미연동 (컨테이너만 존재) | 다중 인스턴스 환경에서 인메모리 큐 교체 대상 |
| 토큰 검증 | `validateToken()` 구현됨, 미사용 | Claim API 연동 시 사용 |
| 부하 테스트 | 미진행 | k6 또는 JMeter로 `/queue/enter` 대량 요청 예정 |
| Nginx 전체 스택 | `docker-compose.yml` 작성됨 | app1/app2 빌드 후 테스트 가능 |
