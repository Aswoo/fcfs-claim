# JVM 실험 결과 리포트

**실행일:** 2026-05-08  
**환경:** Minikube (로컬 K8s), Spring Boot 3.x, JDK 17  
**k6 스크립트:** `k6/01_queue_stress.js` (VU 0→100→0 / 20s 스테이지)

---

## 실험 1 — OOMKilled

### 설정 비교

| 항목 | Bad (01-oom-bad) | Good (01-oom-good) |
|------|------------------|--------------------|
| Memory Limit | 256Mi | 512Mi |
| MaxRAMPercentage | 90% | 60% |
| 계산된 힙 한도 | 256×90% = 230Mi | 512×60% = 307Mi |
| Non-Heap 추정 | ~200Mi | ~200Mi |
| 예상 총 메모리 | ~430Mi (limit 초과) | ~507Mi (limit 여유) |

### 결과

**Bad:** `fcfs-app-d6f4954b` — 기동 직후 **OOMKilled (Exit Code 137)**, CrashLoopBackOff 8회 반복

```
RESTARTS: 8
lastState.reason: OOMKilled
exitCode: 137
```

**Good:** `fcfs-app-6986c47469` — k6 부하(VU 100) 20초 내내 재시작 없이 버팀

```
RESTARTS: 0
lastState.reason: (없음)

k6 결과:
  p(95) 응답 시간: 14.31ms  ✓ (threshold: <500ms)
  에러율:          0.00%     ✓ (threshold: <1%)
  총 처리:         10,570 req / 528 req/s
```

### 원인 분석

JVM은 기동 시 Non-Heap 영역(Metaspace + JIT CodeCache + 스레드 스택)으로 약 200Mi를 선점한다.  
`MaxRAMPercentage=90%`로 힙을 230Mi로 잡으면 합계 430Mi가 되어 256Mi limit을 170Mi 초과한다.  
OS 커널이 `SIGKILL`을 보내며 컨테이너를 강제 종료 → Exit Code = 128 + 9 = **137**.

**핵심 규칙:** `limit × MaxRAMPercentage + Non-Heap(~200Mi) < limit`  
→ limit 512Mi 기준 MaxRAMPercentage 60% 이하로 설정해야 안전.

---

## 실험 2 — CPU 잘못 인식

### 설정 비교

| 항목 | Bad (02-cpu-bad) | Good (02-cpu-good) |
|------|------------------|--------------------|
| JAVA_OPTS | `-XX:-UseContainerSupport` | `-XX:+UseContainerSupport -XX:ActiveProcessorCount=2` |
| CPU Limit | 1000m (1코어) | 1000m (1코어) |

### 결과

**Bad:** JVM `availableProcessors()` = **11개** (노드 전체 CPU 인식)

```
[main] 인식된 CPU 수 : 11개
```

→ ForkJoinPool.commonPool 스레드 = 10개 생성  
→ 실제 1코어에서 10스레드 경쟁 → context switching 급증

**Good:** JVM `availableProcessors()` = **2개** (ActiveProcessorCount 적용)

```
[main] 인식된 CPU 수 : 2개
```

→ ForkJoinPool 스레드 = 1개(최솟값) 생성  
→ 1코어 한도에서 적절한 병렬 수준 유지

### 원인 분석

JDK 8u191+ 이후 `UseContainerSupport`가 기본값 ON이지만,  
`-XX:-UseContainerSupport`로 명시적으로 OFF 하면 JVM이 `/proc/cpuinfo`를 직접 읽어 노드 전체 CPU 수를 사용한다.  
K8s에서 CPU limit은 CFS throttle 방식이므로, 스레드가 아무리 많아도 한도 이상 CPU time을 받지 못한다.  
결과적으로 모든 스레드가 throttle에 걸려 응답 지연이 급증한다.

**핵심 규칙:** K8s 배포 시 `-XX:+UseContainerSupport` 명시 + `ActiveProcessorCount`로 ForkJoinPool 제어.

---

## 실험 3 — GC 로그 분석

### 설정 비교

| 항목 | Logging (03-gc-logging) | Stress (03-gc-stress) |
|------|-------------------------|-----------------------|
| Heap | MaxRAMPercentage=60% (~307Mi) | Xms64m / Xmx160m |
| GC 로그 | `/tmp/gc.log` 롤링 기록 | `/tmp/gc.log` 기록 |
| k6 지속 시간 | 60s | 30s |

### 결과

#### Logging (정상 힙, 60초)

```
Young GC 횟수:   119회
Young GC 평균:   4.86ms
Full GC  횟수:   10회
GC 직전 힙 최대 사용량: 52MB
```

> Full GC가 10회 발생했지만 부하 규모 대비 응답 시간에 유의미한 영향 없음.

#### Stress (힙 64~160MB 강제 축소, 30초)

```
Young GC 횟수:   43회
Young GC 평균:   6.55ms    (+1.7ms, 약 35% 증가)
Full GC  횟수:   3회
Full GC  평균:   23.98ms
Full GC  최대:   30.24ms
```

k6 결과 (30s 제한):
```
p(95) 응답 시간: 14.94ms  ✓ (threshold: <500ms)
에러율:          0.00%    ✓
```

힙이 160MB로 제한되어 Young GC 빈도와 STW 시간이 상승했으나,  
낮은 부하(1 VU)에서는 p(95)가 임계치를 넘지 않았다.  
고부하(VU 100) 환경에서는 Full GC 30ms STW가 p(95) 급등 요인이 될 수 있다.

### GC 읽는 법

```
Pause Young ... 4ms   ← Minor GC, Young Gen만 회수, 보통 무시 가능
Pause Full  ... 30ms  ← Major GC, 힙 전체 STW — 이 시간만큼 앱 멈춤
```

Full GC가 100ms 이상 찍히기 시작하면 힙 크기 또는 GC 알고리즘 조정 필요.

---

## 종합 설정 권고

| 항목 | 권장값 |
|------|--------|
| Memory limit | Non-Heap 200Mi 감안, 최소 512Mi 이상 |
| MaxRAMPercentage | limit의 50~60% 이하 |
| UseContainerSupport | `-XX:+UseContainerSupport` 명시 |
| ActiveProcessorCount | CPU limit에 맞게 설정 (예: 1코어 → 2) |
| GC 로그 | `-Xlog:gc*:file=/tmp/gc.log:time,uptime` 항상 켜두기 |
| 힙 최솟값 | 기동 안정성을 위해 Xms ≥ 128m 권장 |

---

## 실험 환경 요약

```
Cluster:    Minikube (local)
Namespace:  fcfs
Node CPU:   11코어 (Mac host 공유)
Image:      fcfs-claim-app:dev-20260508121637
k6 script:  01_queue_stress.js (stages: 5s→50VU, 10s→100VU, 5s→0VU)
```
