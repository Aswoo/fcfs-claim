# Docker & Docker Compose 입문 가이드

> 이 프로젝트를 기준으로 Docker를 처음 배우는 주니어 개발자를 위한 가이드입니다.

---

## 1. Docker가 뭔데 써야 해?

### 기존 개발의 문제점

```
A 개발자 PC: Java 17, MySQL 8.0, Redis 7 → 잘 됨
B 개발자 PC: Java 11, MySQL 5.7, Redis 6 → 안 됨
서버:        Java 21, MySQL 8.0, Redis 7 → 또 다름
```

"내 컴퓨터에서는 됐는데요?" 문제가 생깁니다.

### Docker의 해결책

코드와 실행 환경을 **컨테이너**라는 박스에 같이 담아버립니다.

```
┌─────────────────────────────┐
│         컨테이너              │
│  코드 + Java 17 + 설정파일    │
│  → 어디서 실행해도 동일하게 동작 │
└─────────────────────────────┘
```

### VM(가상머신)과의 차이

```
VM:        [앱][OS 전체] / [앱][OS 전체]  → 무겁고 느림 (GB 단위)
Docker:    [앱][앱][앱] / [공유 OS 커널]  → 가볍고 빠름 (MB 단위)
```

---

## 2. 핵심 개념 3가지

### Image (이미지)
컨테이너를 만드는 **설계도**입니다. 읽기 전용.

```
mysql:8.0        → MySQL 8.0이 담긴 이미지
nginx:alpine     → Nginx가 담긴 이미지 (alpine = 초경량 Linux)
eclipse-temurin:17-jre-alpine → Java 17 JRE 이미지
```

### Container (컨테이너)
이미지를 실행한 **인스턴스**입니다. 이미지 1개로 컨테이너를 여러 개 만들 수 있습니다.

```
mysql:8.0 이미지 → mysql-container-1 (실행 중)
                 → mysql-container-2 (실행 중)  ← 동시에 여러 개 가능
```

### Dockerfile
**내 앱**을 이미지로 만드는 레시피입니다.

```dockerfile
FROM eclipse-temurin:17-jre-alpine  # 베이스 이미지 (Java 17 환경)
WORKDIR /app                         # 컨테이너 안 작업 디렉토리
COPY app.jar app.jar                 # 내 jar 파일 복사
ENTRYPOINT ["java", "-jar", "app.jar"]  # 실행 명령어
```

---

## 3. 이 프로젝트의 Dockerfile 설명

```dockerfile
# backend/Dockerfile

# ── Stage 1: 빌드 ──────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
# JDK가 필요한 이유: gradlew로 컴파일해야 하므로

WORKDIR /app

# gradle 파일 먼저 복사 → 의존성 캐시 레이어 생성
# 소스만 바뀌면 이 레이어는 재사용 (빌드 속도 향상)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN ./gradlew dependencies --no-daemon

# 소스 복사 후 jar 생성
COPY src src
RUN ./gradlew bootJar --no-daemon

# ── Stage 2: 실행 ──────────────────────────────
FROM eclipse-temurin:17-jre-alpine
# JRE만 있으면 됨: 실행만 하면 되니까 (JDK보다 훨씬 가벼움)

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Stage 1에서 만든 jar만 가져옴
# → 최종 이미지에 빌드 도구(gradle 등)가 포함되지 않음 (보안 + 용량)

ENTRYPOINT ["java", "-jar", "app.jar"]
```

**왜 2단계(Multi-stage)로 나누나?**
- Stage 1(빌드용): JDK + Gradle + 소스 → 빌드 후 버림
- Stage 2(실행용): JRE + jar만 → 최종 이미지에 포함
- 결과: 이미지 크기가 절반 이하로 줄어듦

---

## 4. Docker Compose가 뭔데?

컨테이너 여러 개를 **한 번에 관리**하는 도구입니다.

```bash
# Docker만 쓸 때 — 하나씩 실행해야 함
docker run mysql:8.0 ...
docker run redis:7.0-alpine ...
docker run nginx:alpine ...
docker run my-spring-boot-app ...

# Docker Compose를 쓸 때 — 한 방에
docker compose up
```

---

## 5. 이 프로젝트의 docker-compose.yml 설명

```
fcfs-claim/
├── docker-compose.yml   ← 전체 구성 정의
├── nginx/
│   └── nginx.conf       ← Nginx 설정
└── backend/
    └── Dockerfile       ← Spring Boot 이미지 설계도
```

### 전체 구조

```
외부 요청
    ↓
[Nginx:80]          ← 리버스 프록시 + Rate Limit + 로드밸런서
    ↓         ↘
[app1:8081] [app2:8081]  ← Spring Boot 인스턴스 2개
    ↓
[MySQL:3306] [Redis:6379]  ← 데이터 저장
```

### 각 서비스 역할

| 서비스 | 이미지 | 역할 |
|--------|--------|------|
| nginx | nginx:alpine | 모든 요청의 첫 관문. 분산 + 제한 |
| app1, app2 | (Dockerfile 빌드) | 비즈니스 로직 처리 |
| mysql | mysql:8.0 | 수령 이력 영속 저장 |
| redis | redis:7.0-alpine | 재고 관리, 대기열 |

### depends_on이란?

```yaml
app1:
  depends_on:
    mysql:
      condition: service_healthy
```

`app1`은 `mysql`이 완전히 준비된 후에 시작합니다.
`service_healthy` = healthcheck가 통과한 뒤라는 의미입니다.

### healthcheck란?

```yaml
mysql:
  healthcheck:
    test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
    interval: 10s   # 10초마다 확인
    timeout: 5s     # 5초 안에 응답 없으면 실패
    retries: 5      # 5번 실패하면 unhealthy
```

MySQL이 실제로 쿼리를 받을 준비가 됐는지 주기적으로 확인합니다.
단순히 프로세스가 시작됐다고 DB가 준비된 게 아닙니다.

### volumes란?

```yaml
volumes:
  mysql_data:
  redis_data:
```

컨테이너를 삭제해도 데이터가 살아남습니다.

```
컨테이너 삭제 → 컨테이너 내부 데이터 사라짐 (기본 동작)
볼륨 사용     → 호스트 PC에 별도 저장 → 컨테이너 재생성해도 유지
```

---

## 6. Nginx 설정 설명

```nginx
# nginx/nginx.conf

# ── Rate Limit 존 정의 ─────────────────────────
limit_req_zone $binary_remote_addr zone=queue_zone:10m rate=5r/s;
#              └ IP 기준으로 집계   └ 존 이름:메모리  └ 초당 5개 허용

# ── 로드밸런싱 대상 ────────────────────────────
upstream backend {
    server app1:8081;   # docker-compose 서비스명 사용 가능
    server app2:8081;   # 기본값: round-robin (1→2→1→2 순서)
}

# ── 요청 처리 ─────────────────────────────────
location /api/v1/queue/enter {
    limit_req zone=queue_zone burst=5 nodelay;
    # burst=5: 순간적으로 5개까지 허용
    # nodelay: 초과분 즉시 429 반환 (줄 세우지 않음)
    proxy_pass http://backend;
}
```

**Rate Limit 동작 예시:**

```
1초 안에 IP 1.2.3.4에서 요청 8개 들어옴
→ 5개: 정상 처리
→ 3개: 429 Too Many Requests 반환
```

**round-robin 동작 예시:**

```
요청 1 → app1
요청 2 → app2
요청 3 → app1
요청 4 → app2  ...
```

---

## 7. Spring Boot 프로파일 설명

```
application.yml         ← 로컬 개발용 (H2 인메모리)
application-docker.yml  ← Docker 환경용 (MySQL 연결)
```

Docker Compose에서 환경변수로 프로파일을 지정합니다:

```yaml
app1:
  environment:
    SPRING_PROFILES_ACTIVE: docker  # application-docker.yml 사용
    DB_USERNAME: root
    DB_PASSWORD: password
```

`application-docker.yml` 안에서 환경변수를 받아씁니다:

```yaml
spring:
  datasource:
    username: ${DB_USERNAME:root}   # 환경변수 없으면 기본값 root 사용
    password: ${DB_PASSWORD:password}
    url: jdbc:mysql://mysql:3306/fcfs_claim  # 서비스명 mysql로 접속
```

---

## 8. 자주 쓰는 명령어

```bash
# 전체 실행 (백그라운드)
docker compose up -d

# 전체 실행 + 로그 보기
docker compose up

# 특정 서비스만 재빌드 후 실행
docker compose up -d --build app1

# 전체 종료
docker compose down

# 전체 종료 + 볼륨(데이터)도 삭제
docker compose down -v

# 실행 중인 컨테이너 목록
docker compose ps

# 특정 서비스 로그 보기
docker compose logs -f app1

# 컨테이너 내부 접속 (디버깅)
docker compose exec app1 sh
docker compose exec mysql mysql -u root -p fcfs_claim
```

---

## 9. 처음 실행하는 순서

```bash
# 1. 프로젝트 루트로 이동
cd /Users/sdu/fcfs-claim

# 2. 이미지 빌드 + 전체 실행
docker compose up --build

# 3. 로그에서 아래 메시지 확인
# app1 | Started FcfsClaimApplication in X seconds
# app2 | Started FcfsClaimApplication in X seconds

# 4. API 테스트 (Nginx 통해서 접근)
curl http://localhost/api/v1/queue/enter
# → Spring Boot가 직접 응답하는 게 아니라 Nginx → app1 or app2 로 전달됨
```

---

## 10. 트러블슈팅

### app1이 바로 죽어요
MySQL이 아직 준비 안 됐을 가능성이 높습니다.

```bash
docker compose logs mysql   # MySQL 로그 확인
docker compose ps           # healthy 상태 확인
```

### 포트가 이미 사용 중이에요
로컬에서 MySQL이나 Redis가 이미 실행 중인 경우입니다.

```bash
# 로컬 MySQL 종료 후 재시도
brew services stop mysql

# 또는 docker-compose.yml에서 포트 변경
ports:
  - "3307:3306"   # 외부 3307 → 컨테이너 3306
```

### 이미지를 완전히 새로 빌드하고 싶어요

```bash
docker compose build --no-cache
docker compose up -d
```

---

## 11. 이 프로젝트의 흐름 요약

```
1. docker compose up --build

2. MySQL 컨테이너 시작 → healthcheck 통과
3. Redis 컨테이너 시작
4. app1, app2 시작 (MySQL 준비 후)
5. Nginx 시작 (app1, app2 준비 후)

6. 요청 흐름:
   폰(Expo Go)
     → Nginx:80 (rate limit 체크)
     → app1 or app2:8081 (비즈니스 로직)
     → MySQL/Redis (데이터)
     → 응답 반환
```
