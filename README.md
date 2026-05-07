# FCFS-Claim

선착순 한정 수량 지급 시스템 — 대기열 기반 동시성 제어 실습 프로젝트

---

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [시스템 아키텍처](#시스템-아키텍처)
3. [기술 스택](#기술-스택)
4. [핵심 구현 포인트](#핵심-구현-포인트)
5. [API 명세](#api-명세)
6. [로컬 실행](#로컬-실행)
7. [Kubernetes 배포](#kubernetes-배포)
8. [부하 테스트 (k6)](#부하-테스트-k6)
9. [테스트 코드](#테스트-코드)
10. [JVM 실험](#jvm-실험)
11. [프로젝트 구조](#프로젝트-구조)

---

## 프로젝트 개요

한정 수량 상품을 선착순으로 지급하는 서버 시스템. 수백 명이 동시에 접근해도 재고가 정확히 차감되고, 중복 수령이 발생하지 않아야 한다.

**해결해야 할 문제들**

| 문제 | 해결 방식 |
|------|----------|
| 동시 클릭 → 재고 초과 차감 | DB 조건부 UPDATE (`stock > 0`) |
| 다중 인스턴스 → SSE 수신 누락 | Redis Pub/Sub 브로드캐스트 |
| 스케줄러 중복 실행 | ShedLock (Redis 기반 분산 락) |
| 서버 재시작 → 이벤트 유실 | ApplicationRunner 복구 로직 |
| 토큰 상태 Redis/DB 불일치 | 60초 배치 동기화 |

---

## 시스템 아키텍처

```
[사용자]
   │
   ▼
[Nginx]  ← 리버스 프록시 / 로드밸런서 / Rate Limit
   │
   ├──▶ [App 인스턴스 1]  ─┐
   └──▶ [App 인스턴스 2]  ─┤─▶ [MySQL]   (영구 저장: 이벤트, 토큰, 수령 이력)
                            └─▶ [Redis]   (대기열 ZSET, 토큰, Pub/Sub, ShedLock)
```

**멀티 인스턴스 SSE 흐름**

```
스케줄러(ShedLock) → app1이 선점
  └─▶ 토큰 발급 (Redis + DB 저장)
  └─▶ Redis Pub/Sub "queue:ready" 발행
        ├─▶ app1 구독 → 이 인스턴스 SSE 스토어에서 userId 찾아 전송
        └─▶ app2 구독 → 이 인스턴스 SSE 스토어에서 userId 찾아 전송
```

---

## 기술 스택

**백엔드**

| 항목 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3.x |
| 언어 | Java 17 |
| ORM | Spring Data JPA (Hibernate) |
| DB | MySQL 8.0 |
| 캐시/큐 | Redis 7.0 |
| 분산 락 | ShedLock (Redis Provider) |
| 실시간 | SSE (Server-Sent Events) |
| 빌드 | Gradle |

**프론트엔드**

| 항목 | 기술 |
|------|------|
| 프레임워크 | React Native (Expo) |
| 언어 | TypeScript |
| 상태 관리 | Zustand + TanStack Query |
| 네비게이션 | React Navigation v7 |

**인프라**

| 항목 | 기술 |
|------|------|
| 컨테이너 | Docker / Docker Compose |
| 오케스트레이션 | Kubernetes (Docker Desktop) |
| 오토스케일링 | HPA (CPU 50% 기준, 최대 5 파드) |
| 부하 테스트 | k6 |

---

## 핵심 구현 포인트

### 1. 대기열 (Redis ZSET)

```
POST /api/v1/queue/enter
  → redis.ZADD queue:waiting:{eventId} NX (score=timestamp, member=userId)
  → 동일 userId 중복 입장 방지 (NX 옵션)
  → 즉시 현재 순번(rank) 반환
```

`processQueue` 스케줄러가 1초마다 이벤트별 대기열에서 10명씩 ZPOPMIN으로 꺼내 토큰을 발급한다. **ShedLock으로 다중 인스턴스 중 1개만 실행**되도록 보장한다.

### 2. 토큰 발급 & 검증

- 발급: `UUID` 생성 → Redis `token:{eventId}:{token}` (TTL 300초) + DB 저장
- 검증: claim 요청 시 Redis 키 존재 여부 확인 → 없으면 401
- 소진: claim 성공 시 Redis 키 삭제 + DB status=USED

### 3. Redis Pub/Sub (멀티 인스턴스 SSE)

```java
// 토큰 발급 후 발행 (어느 인스턴스든 발행 가능)
redis.convertAndSend("queue:ready", json(eventId, userId, token));

// 모든 인스턴스가 구독, 자기 SSE 스토어에서 해당 userId 검색
QueueReadySubscriber.onMessage() → SseEmitterStore.get(userId) → emitter.send()
```

### 4. 이벤트 생명주기 (SCHEDULED → ACTIVE → ENDED)

```
서버 시작
  └─▶ EventRecoveryService (ApplicationRunner)
        ├─ 이미 종료됐어야 할 이벤트 → 즉시 endEvent()
        ├─ 활성화됐어야 할 이벤트   → 즉시 activateEvent()
        ├─ ACTIVE 이벤트           → 종료 TaskScheduler 재등록
        └─ SCHEDULED 이벤트        → 활성화 + 종료 TaskScheduler 등록

TaskScheduler.schedule(Instant) → 정각에 activateEvent() / endEvent() 호출
  └─▶ 프로그래매틱 ShedLock ("activateEvent-{id}", "endEvent-{id}")
        → 다중 파드 중 1개만 실행
```

### 5. ActiveEventCache (DB 조회 제거)

`processQueue`는 1초마다 실행되므로 매번 DB 조회 시 부하가 크다. 인메모리 `Set<Long>`으로 ACTIVE 이벤트 ID를 캐시하고, 이벤트 상태 변경 시 즉시 반영한다.

```java
// 캐시 즉시 반영 (30초 주기 refresh와 병행)
activeEventCache.add(eventId);    // activateEvent() 시
activeEventCache.remove(eventId); // endEvent() 시
```

### 6. 재고 차감 동시성

```sql
UPDATE product SET stock = stock - 1
WHERE id = ? AND stock > 0
```

`affected rows = 0` 이면 재고 소진 → 409 반환. 트랜잭션 롤백으로 이미 저장된 Claim 레코드도 함께 롤백된다.

---

## API 명세

### 이벤트

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/events/{id}/status` | 이벤트 상태 조회 (SCHEDULED/ACTIVE/ENDED) |

### 대기열

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/queue/enter` | 대기열 입장, 현재 순번 반환 |
| GET | `/api/v1/queue/status` | 토큰 발급 여부 / 현재 순번 확인 |
| GET | `/api/v1/queue/sse` | SSE 연결 (토큰 발급 시 push) |

### 상품 & 수령

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v1/events/{id}/products` | 이벤트 상품 목록 (재고 포함) |
| POST | `/api/v1/claim` | 상품 수령 (토큰 필수) |

### 관리자 (테스트 전용)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/admin/reset` | 수령 이력, 토큰, Redis 대기열 초기화 |
| POST | `/api/v1/admin/force-activate/{eventId}` | 이벤트 즉시 활성화 |
| POST | `/api/v1/admin/force-end/{eventId}` | 이벤트 즉시 종료 |
| POST | `/api/v1/admin/force-schedule/{eventId}` | 이벤트 SCHEDULED 상태로 리셋 |
| DELETE | `/api/v1/admin/token?eventId=&token=` | 토큰 만료 시뮬레이션 |

---

## 로컬 실행

### 사전 요구사항

- Docker Desktop
- Java 17+
- Node.js 18+

### Docker Compose (단일 인스턴스)

```bash
cd fcfs-claim
docker compose up --build
```

서버: `http://localhost:80` (Nginx) / `http://localhost:8081` (앱 직접)

### Docker Compose (다중 인스턴스 — SSE 멀티 인스턴스 테스트)

```bash
docker compose up --build --scale app=2
```

Nginx가 두 인스턴스에 라운드로빈으로 분산한다.

### 백엔드 단독 실행 (개발용)

```bash
cd backend
./gradlew bootRun
```

H2 인메모리 DB 사용, `http://localhost:8081`

### 프론트엔드

```bash
cd frontend
npm install
npx expo start --port 8082
```

---

## Kubernetes 배포

### 사전 요구사항

- Docker Desktop (Kubernetes 활성화)
- kubectl

### 배포

```bash
# 이미지 빌드 (Docker Desktop K8s는 로컬 이미지를 직접 사용)
docker build -t fcfs-claim-app:latest ./backend

# 전체 리소스 배포
kubectl apply -f k8s/
```

또는 배포 스크립트 사용:

```bash
./k8s/deploy.sh
```

### 확인

```bash
kubectl get all -n fcfs
kubectl logs -n fcfs -l app=fcfs-app -f
```

### HPA 동작 확인

```bash
kubectl get hpa -n fcfs -w   # CPU 50% 초과 시 파드 자동 증가 (최대 5개)
```

---

## 부하 테스트 (k6)

### 사전 요구사항

```bash
brew install k6
```

### 테스트 파일

| 파일 | 목적 |
|------|------|
| `01_queue_stress.js` | 대기열 입장 API 부하 테스트 (100 VU, 20초) |
| `02_claim_race.js` | 동시 claim 레이스 컨디션 검증 |
| `03_full_flow.js` | 입장 → 폴링 → 수령 전체 흐름 |
| `04_lifecycle_boundary.js` | 이벤트 생명주기 경계 테스트 |
| `05_expired_token.js` | 만료 토큰 거부 테스트 |

### 실행 순서

```bash
k6 run k6/04_lifecycle_boundary.js
k6 run k6/02_claim_race.js
k6 run k6/03_full_flow.js
k6 run k6/05_expired_token.js
k6 run k6/01_queue_stress.js
```

> 자세한 내용 → [`docs/k6-load-test.md`](docs/k6-load-test.md)

---

## 테스트 코드

```bash
./gradlew test
```

### 테스트 구성

| 클래스 | 방식 | 케이스 수 | 핵심 검증 |
|--------|------|-----------|-----------|
| `ClaimServiceTest` | Mockito | 6 | 토큰 검증, 중복 수령, 재고 소진 시 Redis 미삭제 |
| `QueueServiceTest` | Mockito | 6 | 대기열 입장, 상태 조회, 토큰 검증 |
| `ProductRepositoryTest` | `@DataJpaTest` (H2) | 3 | 재고 차감 쿼리, **100스레드 동시성 검증** |
| `EventLifecycleServiceTest` | Mockito | 4 | ShedLock 락 경합 스킵, 이벤트 상태 전이 |
| `ClaimControllerTest` | `@WebMvcTest` | 3 | HTTP 상태 코드 매핑 |

### 테스트 계층 전략

```
단위 테스트 (Mockito)       → 서비스 비즈니스 로직. Redis/DB 전부 Mock
Repository 테스트           → JPA 커스텀 쿼리. H2 실제 실행
Controller 슬라이스 테스트   → HTTP 레이어. MockMvc
```

통합 테스트(`@SpringBootTest`)는 Redis 실서버가 필요하고 무거워서 제외했다.

> 자세한 내용 → [`docs/testing.md`](docs/testing.md)

---

## JVM 실험

K8s 컨테이너 환경에서 실제로 발생하는 JVM 문제를 재현하고 해결하는 실습.

### 실험 목록

| 실험 | 재현 내용 | 핵심 학습 |
|------|-----------|-----------|
| `oom-bad` → `oom-good` | OOMKilled 재현 후 해결 | Heap + Non-Heap 합계가 limit 초과하면 OS가 프로세스 강제 종료 |
| `cpu-bad` → `cpu-good` | CPU 잘못 인식 재현 후 해결 | `UseContainerSupport` OFF 시 노드 전체 CPU를 봄 |
| `gc-stress` | Full GC 유발 | 힙 압박 시 Stop-the-World → p(95) 응답 시간 급등 |

### 실행 방법

```bash
# 실험 스크립트 (YAML 적용 + k6 자동 실행 + 결과 출력)
./k8s/experiments/run.sh

# 실험 1 — OOMKilled
터미널 A: ./k8s/experiments/run.sh oom-bad    # k6 자동 실행
터미널 B: ./k8s/experiments/run.sh oom-watch  # 파드 상태 3초마다 polling

# 실험 3 — GC
터미널 A: ./k8s/experiments/run.sh gc-stress  # k6 자동 실행
터미널 B: ./k8s/experiments/run.sh gc-tail    # GC 로그 실시간 스트리밍
```

### OOMKilled 확인 포인트

```bash
# 1. 죽기 직전 로그 — 에러 메시지 없이 끊기면 OOMKilled 의심
kubectl logs -n fcfs <파드> --previous

# 2. 원인 확인
kubectl describe pod -n fcfs <파드> | grep -A 6 "Last State"
# Reason: OOMKilled / Exit Code: 137

# 3. 현재 메모리 압박 수준
kubectl top pod -n fcfs
# limit 대비 80% 넘으면 위험
```

> 자세한 내용 → [`docs/jvm-oom-experiment.md`](docs/jvm-oom-experiment.md)

---

## 프로젝트 구조

```
fcfs-claim/
├── backend/                     # Spring Boot 백엔드
│   └── src/
│       ├── main/java/com/example/fcfsclaim/
│       │   ├── common/
│       │   │   ├── config/      # RedisConfig, SchedulerConfig
│       │   │   ├── init/        # DataInitializer (초기 데이터)
│       │   │   └── response/    # ApiResponse 공통 응답 래퍼
│       │   └── domain/
│       │       ├── admin/       # 테스트용 reset / force-* API
│       │       ├── claim/       # 상품 수령 (재고 차감)
│       │       ├── event/       # 이벤트 생명주기, 상태 조회
│       │       ├── product/     # 상품 목록
│       │       └── queue/       # 대기열, SSE, 토큰, Pub/Sub
│       └── test/                # 단위/슬라이스 테스트
│           └── domain/
│               ├── claim/       # ClaimServiceTest, ClaimControllerTest
│               ├── event/       # EventLifecycleServiceTest
│               ├── product/     # ProductRepositoryTest (동시성)
│               └── queue/       # QueueServiceTest
│
├── frontend/                    # React Native (Expo)
│   └── src/
│       ├── features/            # 화면별 모듈
│       ├── shared/components/   # 공통 컴포넌트
│       ├── navigation/
│       └── services/            # API 레이어
│
├── k6/                          # 부하 테스트 스크립트
│   ├── shared/helpers.js        # 공통 유틸 (waitForEventActive 등)
│   ├── 01_queue_stress.js
│   ├── 02_claim_race.js
│   ├── 03_full_flow.js
│   ├── 04_lifecycle_boundary.js
│   └── 05_expired_token.js
│
├── k8s/                         # Kubernetes 매니페스트
│   ├── 00-namespace.yaml
│   ├── 01-mysql.yaml            # MySQL + PersistentVolumeClaim
│   ├── 02-redis.yaml
│   ├── 03-app.yaml              # fcfs-app Deployment + Service
│   ├── 04-hpa.yaml              # HorizontalPodAutoscaler
│   ├── deploy.sh
│   └── experiments/             # JVM 실험용 매니페스트
│       ├── 01-oom-bad.yaml      # OOMKilled 재현 (256Mi + MaxRAM=90%)
│       ├── 01-oom-good.yaml     # OOMKilled 해결 (512Mi + MaxRAM=60%)
│       ├── 02-cpu-bad.yaml      # CPU 잘못 인식 (UseContainerSupport OFF)
│       ├── 02-cpu-good.yaml     # CPU 올바른 설정
│       ├── 03-gc-logging.yaml   # GC 로그 활성화
│       ├── 03-gc-stress.yaml    # GC 압박 (힙 64MB 강제 축소)
│       └── run.sh               # 실험 자동화 스크립트
│
├── nginx/nginx.conf             # 리버스 프록시 + Rate Limit 설정
├── docker-compose.yml           # 로컬 다중 인스턴스 환경
├── plan/                        # 구현/실험 계획 문서
│   ├── test-plan.md
│   └── jvm-experiments.md
└── docs/                        # 설계 문서 및 학습 자료
    ├── architecture.md          # 컴포넌트 구조, 핵심 설계 결정
    ├── domain-model.md          # 엔티티 상세, 도메인 간 흐름
    ├── db-schema.md             # 테이블 정의, Redis 키 구조
    ├── testing.md               # 테스트 전략, 계층별 설명
    ├── jvm-oom-experiment.md    # OOMKilled 실제 로그 기반 분석 가이드
    ├── k6-load-test.md          # 부하 테스트 스크립트 설명
    ├── docker-guide.md          # Docker 입문 가이드
    ├── kubernetes-guide.md      # K8s 입문 + 실제 문제 해결
    ├── pubsub-and-expiry.md     # Redis Pub/Sub & 토큰 만료 배치
    ├── redis-migration.md       # 인메모리 → Redis 전환 과정
    └── interview-qa.md          # 면접 Q&A
```
