# JVM 튜닝 실험 가이드

> 컨테이너/K8s 환경에서 JVM이 일으키는 실제 문제를 재현하고 해결하는 실습.
> 총 3가지 실험. 순서대로 진행한다.

---

## JVM 메모리 구조 — 실험 전에 반드시 알아야 할 것

JVM이 사용하는 메모리는 크게 두 덩어리다.

```
┌─────────────────────────────────────────────┐
│              JVM 전체 메모리                  │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │            Heap (힙)                │    │
│  │  ┌──────────────┐ ┌─────────────┐  │    │
│  │  │ Young Gen    │ │  Old Gen    │  │    │
│  │  │ (새 객체)    │ │  (오래된)   │  │    │
│  │  └──────────────┘ └─────────────┘  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │         Non-Heap (힙 외)            │    │
│  │  Metaspace │ Code Cache │ Threads   │    │
│  │  (클래스)  │ (JIT 컴파일)│ (스택)   │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

**Heap**: `new` 로 생성한 객체들이 사는 곳. GC가 관리한다. `-Xmx`로 최대 크기 설정.

**Metaspace**: 클래스 정의(바이트코드)가 사는 곳. Spring이 클래스를 많이 로딩한다. 기본값 제한 없음.

**Code Cache**: JIT 컴파일러가 바이트코드를 네이티브 코드로 변환해 저장. 기본 240MB.

**Thread Stack**: 스레드 하나당 기본 1MB. 스레드 200개 = 200MB.

**핵심:** K8s 메모리 limit은 JVM이 쓰는 **전체** 메모리에 걸린다. Heap만이 아니다.

```
컨테이너 limit = 512Mi
JVM이 실제로 쓰는 메모리:
  Heap     300Mi
  Metaspace 100Mi
  Threads   50Mi
  기타      50Mi
  ──────────────
  합계      500Mi  ← 512Mi에 아슬아슬하게 들어옴

k6 부하로 Heap이 330Mi로 늘어나면?
  합계      530Mi  → 컨테이너 limit 초과 → OOMKilled
```

---

## 실험 준비: JVM 상태를 보여주는 로그 추가

실험마다 JVM이 뭘 보고 있는지 앱 시작 로그로 확인한다.

`FcfsClaimApplication.java` 수정:

```java
@SpringBootApplication
@EnableScheduling
public class FcfsClaimApplication {

    private static final Logger log = LoggerFactory.getLogger(FcfsClaimApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FcfsClaimApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printJvmInfo() {
        Runtime rt = Runtime.getRuntime();
        long maxHeapMb  = rt.maxMemory() / 1024 / 1024;
        long totalHeapMb = rt.totalMemory() / 1024 / 1024;
        int cpus = rt.availableProcessors();

        log.info("======== JVM 정보 ========");
        log.info("최대 힙 (Xmx)    : {}MB", maxHeapMb);
        log.info("현재 힙 (할당됨) : {}MB", totalHeapMb);
        log.info("인식된 CPU 수    : {}개", cpus);
        log.info("=========================");
    }
}
```

이 로그가 각 실험마다 얼마나 달라지는지 보는 것이 핵심이다.

---

## 실험 1 — OOMKilled 재현과 해결

### 배경: 컨테이너는 JVM을 모른다

K8s는 컨테이너 limit을 Linux cgroup으로 강제한다. 프로세스가 limit을 넘으면 OS가 강제로 죽인다. Java `OutOfMemoryError`와 다르다.

```
Java OutOfMemoryError:  JVM 내부에서 힙이 꽉 찬 것. JVM이 예외를 던진다.
OOMKilled:              컨테이너 limit 초과. OS(cgroup)가 프로세스를 강제 종료.
                        아무 에러 메시지 없이 파드가 죽는다.
```

### 왜 이 문제가 생기나?

JDK 11부터 `UseContainerSupport`가 기본 ON이다. JVM이 컨테이너 limit을 읽어서 힙을 잡는다.
하지만 개발자가 `MaxRAMPercentage`를 너무 높게 설정하면 힙 + Non-Heap 합계가 limit을 초과해 죽는다.

```
limit = 256Mi
MaxRAMPercentage = 90%  ← 잘못된 설정

힙 한도  = 256Mi × 90% = 230Mi
Metaspace  = ~100Mi  (Spring Boot 로딩만 해도 이 정도)
Threads    =  ~50Mi
Code Cache =  ~50Mi
합계       = 430Mi   → limit(256Mi) 대비 170Mi 초과 → OOMKilled
```

### Step 1. 문제 상황 만들기

`k8s/experiments/01-oom-bad.yaml` 적용:

```yaml
# 메모리 limit을 작게 + MaxRAMPercentage를 너무 높게 설정
resources:
  limits:
    memory: "256Mi"
env:
  - name: JAVA_OPTS
    value: "-XX:MaxRAMPercentage=90.0"
```

```bash
# 실험용 매니페스트 적용
kubectl apply -f k8s/experiments/01-oom-bad.yaml

# 파드 상태를 계속 지켜본다
kubectl get pod -n fcfs -w

# k6로 부하를 줘서 메모리 사용량을 끌어올린다
k6 run k6/01_queue_stress.js
```

**예상되는 일:**

```bash
# kubectl get pod -w 출력
NAME                        READY   STATUS    RESTARTS
fcfs-app-xxx                1/1     Running   0          ← 처음엔 정상
fcfs-app-xxx                0/1     OOMKilled 1          ← 부하 주면 죽음
fcfs-app-xxx                0/1     CrashLoopBackOff 1   ← K8s가 재시작 시도
```

```bash
# OOMKilled 확인 명령어
kubectl describe pod -n fcfs <파드이름>
# 출력에서 아래 부분을 찾는다:
# Last State:   Terminated
#   Reason:     OOMKilled     ← 이게 보이면 성공
#   Exit Code:  137           ← 137 = 128 + SIGKILL(9)
```

```bash
# 메모리 사용량 실시간 확인 (죽기 직전 수치를 봐라)
kubectl top pod -n fcfs
```

### Step 2. JVM이 뭘 보고 있는지 확인

```bash
# 파드 로그에서 JVM 정보 출력 확인
kubectl logs -n fcfs <파드이름> | grep "JVM 정보" -A 5

# 출력 예시:
# 최대 힙 (Xmx)    : 230MB   ← 256Mi의 90%
# 현재 힙 (할당됨) : 64MB
# 인식된 CPU 수    : 8개     ← 노드 전체 CPU를 봄
```

### Step 3. 올바른 설정으로 해결

`k8s/experiments/01-oom-good.yaml` 적용:

```yaml
resources:
  limits:
    memory: "512Mi"    # limit을 충분히 줌
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=60.0
      -XX:MinRAMPercentage=50.0
```

```
limit = 512Mi
힙 한도  = 512Mi × 60% = 307Mi
Non-Heap = ~200Mi
합계     = 507Mi  ← 512Mi에 여유 있게 들어옴
```

```bash
kubectl apply -f k8s/experiments/01-oom-good.yaml
kubectl logs -n fcfs <파드이름> | grep "JVM 정보" -A 5

# 출력 예시:
# 최대 힙 (Xmx)    : 307MB   ← 512Mi의 60%
# 인식된 CPU 수    : 8개
```

k6 부하를 다시 줘도 OOMKilled가 발생하지 않는다.

### 실험 1 정리

| 설정 | Heap 한도 | Non-Heap | 합계 | 결과 |
|------|-----------|----------|------|------|
| limit=256Mi, MaxRAM=90% | 230Mi | ~200Mi | ~430Mi | OOMKilled |
| limit=512Mi, MaxRAM=60% | 307Mi | ~200Mi | ~507Mi | 정상 |

**실무 가이드라인:**
- `MaxRAMPercentage`: 60~75% 사이가 안전 (Non-Heap 여유 확보)
- limit은 `MaxRAMPercentage×limit + 200Mi(Non-Heap 예비)` 이상으로 설정
- 의심되면 `kubectl describe pod`에서 `OOMKilled` 확인

---

## 실험 2 — CPU 스로틀링과 스레드 풀 문제

### 배경: JVM이 CPU를 잘못 인식하면

`Runtime.getRuntime().availableProcessors()`는 JVM이 사용 가능한 CPU 수를 반환한다.
Spring이 내부적으로 이 값을 보고 스레드 풀 크기를 결정하는 곳이 여러 곳이다.

```
ForkJoinPool (병렬 스트림)  : availableProcessors - 1 개
Tomcat 스레드 풀            : 기본 200개 (CPU 무관)
ShedLock TaskScheduler      : 코드에 명시 (풀 크기 2)
```

노드에 CPU 8개 있는데 컨테이너 limit이 1000m(1코어)이면:

```
UseContainerSupport OFF:
  availableProcessors() = 8  ← 노드 전체를 봄
  ForkJoinPool = 7스레드 생성
  CPU 1개에서 7스레드가 경쟁 → context switching 폭발

UseContainerSupport ON:
  availableProcessors() = 1  ← 컨테이너 limit(1000m)을 봄
  ForkJoinPool = 1스레드 (최소 1)
```

### Step 1. CPU 인식 차이 확인

```bash
# 현재 상태 (UseContainerSupport ON, 기본값)
kubectl logs -n fcfs <파드이름> | grep "인식된 CPU"
# → 인식된 CPU 수 : 1개  (limit 1000m = 1코어)
```

`k8s/experiments/02-cpu-bad.yaml` 적용 (UseContainerSupport OFF):

```yaml
env:
  - name: JAVA_OPTS
    value: "-XX:-UseContainerSupport"  # 컨테이너 인식 끔
```

```bash
kubectl apply -f k8s/experiments/02-cpu-bad.yaml
kubectl logs -n fcfs <파드이름> | grep "인식된 CPU"
# → 인식된 CPU 수 : 8개  ← 노드 전체 CPU를 봄
```

### Step 2. 스로틀링 실제로 보기

CPU 스로틀링은 컨테이너가 CPU limit을 초과할 때 발생한다. OS가 프로세스 실행을 강제로 일시 정지시킨다.

```bash
# k6로 CPU 부하를 준 상태에서
k6 run k6/01_queue_stress.js &

# 다른 터미널에서 스로틀링 수치 확인
kubectl exec -n fcfs <파드이름> -- cat /sys/fs/cgroup/cpu/cpu.stat
# 출력:
# nr_periods   1000       ← CPU 측정 주기 수
# nr_throttled 430        ← 스로틀된 횟수
# throttled_time 2300000  ← 스로틀된 나노초 합계
```

`nr_throttled / nr_periods`가 높으면 CPU가 자주 막히고 있다는 뜻이다.

### Step 3. 올바른 설정

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+UseContainerSupport
      -XX:ActiveProcessorCount=2
```

`ActiveProcessorCount`로 JVM에게 "CPU 2개로 생각해라"고 명시한다.
limit이 1000m(1코어)인데 `2`로 설정하는 이유: ForkJoinPool 최솟값이 1이라 1로 설정하면 병렬 처리가 완전히 꺼진다. 실제 스루풋은 limit이 제어하므로 2 정도가 적절하다.

### 실험 2 정리

| 설정 | availableProcessors() | ForkJoinPool | 결과 |
|------|----------------------|--------------|------|
| UseContainerSupport OFF | 8 (노드 전체) | 7스레드 | Context switching 폭발 |
| UseContainerSupport ON | 1 (1000m 기준) | 1스레드 | 병렬성 없음 |
| UseContainerSupport ON + ActiveProcessorCount=2 | 2 | 1스레드 | 균형 |

---

## 실험 3 — GC 로그로 메모리 압박 관찰

### 배경: GC가 뭔가

Heap이 꽉 차기 전에 JVM이 주기적으로 "안 쓰는 객체를 치우는" 작업을 한다. 이게 GC(Garbage Collection)다.
JDK 11+의 기본 GC는 G1(Garbage First).

```
Young GC (Minor GC)  : Young Gen이 꽉 찼을 때. 빠름 (수 ms)
Old GC (Major/Full GC): Old Gen까지 꽉 찼을 때. 느림 (수백 ms ~ 수 초)
                        이 시간 동안 앱이 완전히 멈춘다 (Stop-the-World)
```

k6 부하를 주면 요청마다 객체가 생성되고 → Young Gen이 빨리 차고 → Young GC가 자주 발생한다.
GC가 너무 자주 일어나거나 Old GC가 발생하면 응답 지연이 생긴다.

### Step 1. GC 로그 활성화

`k8s/experiments/03-gc-logging.yaml` 적용:

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=60.0
      -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=3,filesize=10m
      -Xlog:gc+heap=debug
```

플래그 설명:
- `-Xlog:gc*` : GC 관련 모든 로그
- `file=/tmp/gc.log` : 파일에 저장
- `time,uptime` : 타임스탬프 포함
- `filecount=3,filesize=10m` : 최대 3개 파일, 각 10MB (롤링)

```bash
kubectl apply -f k8s/experiments/03-gc-logging.yaml
kubectl get pod -n fcfs  # 파드 이름 확인
```

### Step 2. 부하를 주면서 GC 실시간 관찰

```bash
# 터미널 1: k6 부하
k6 run --duration 60s k6/01_queue_stress.js

# 터미널 2: GC 로그 실시간 스트리밍
kubectl exec -n fcfs <파드이름> -- tail -f /tmp/gc.log
```

**GC 로그 읽는 법:**

```
[2026-05-07T10:00:01.234+0000][1.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(128M) 4.123ms
                                                                                                    ↑    ↑   ↑    ↑
                                                                                              GC 전  GC 후  힙  소요시간
```

| 항목 | 설명 |
|------|------|
| `Pause Young (Normal)` | Young GC. 짧고 자주 발생 → 정상 |
| `Pause Young (Concurrent Start)` | Old GC 시작 신호. 주의 |
| `Pause Full` | Full GC. 앱이 멈추는 시간. 자주 보이면 문제 |
| `24M->8M(128M)` | GC 전 24MB 사용 → GC 후 8MB → 힙 최대 128MB |
| `4.123ms` | GC 소요 시간. Young은 10ms 이하가 정상 |

### Step 3. GC 압박 상황 만들기

힙을 강제로 작게 줘서 GC가 자주 일어나게 만든다.

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -Xms32m
      -Xmx64m
      -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags
```

```bash
kubectl apply -f k8s/experiments/03-gc-stress.yaml
k6 run --duration 30s k6/01_queue_stress.js
kubectl exec -n fcfs <파드이름> -- tail -20 /tmp/gc.log
```

**압박 상태에서 나오는 로그:**

```
[10.123s] GC(5)  Pause Young (Normal) 58M->52M(64M) 12.4ms   ← 회수량이 적음 (52→58은 금방 다 참)
[10.456s] GC(6)  Pause Young (Normal) 62M->60M(64M) 18.7ms   ← 점점 회수량 줄어듦
[10.789s] GC(7)  Pause Young (Concurrent Start) 63M->62M(64M) ← Old GC 시작
[11.234s] GC(7)  Pause Full (G1 Compaction Pause) 62M->40M(64M) 234.5ms  ← Full GC! 234ms 동안 앱 멈춤
```

k6 리포트에서 p(95) 응답 시간이 갑자기 치솟는 구간이 이 Full GC 타이밍과 일치한다.

### Step 4. GC 로그 파일 가져와서 분석

```bash
# 파드에서 로컬로 복사
kubectl cp fcfs/<파드이름>:/tmp/gc.log ./gc.log

# GC 통계 빠르게 보기
grep "Pause" gc.log | awk '{print $NF}' | sort -n | tail -20
# → 가장 오래 걸린 GC TOP 20 (ms 단위)

# Full GC 횟수
grep "Pause Full" gc.log | wc -l

# Young GC 평균 시간
grep "Pause Young" gc.log | grep -oE '[0-9]+\.[0-9]+ms' | \
  awk -F'ms' '{sum+=$1; count++} END {printf "평균 Young GC: %.2fms (%d회)\n", sum/count, count}'
```

### Step 5. 정상 설정으로 비교

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=60.0
      -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags
      -XX:+UseG1GC
      -XX:MaxGCPauseMillis=200
```

`MaxGCPauseMillis=200`: GC가 최대 200ms 안에 끝나도록 G1이 힙을 조절. 목표값이지 보장값은 아니다.

---

## 실험 4 — Heap Dump로 메모리 누수 확인 (보너스)

### 배경

OOMKilled가 아니라 Java OutOfMemoryError가 발생할 때, 힙 덤프를 떠서 어떤 객체가 메모리를 먹는지 분석한다.

```yaml
env:
  - name: JAVA_OPTS
    value: >-
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/heapdump.hprof
```

```bash
# OOM 발생 후 힙 덤프 파일 복사
kubectl cp fcfs/<파드이름>:/tmp/heapdump.hprof ./heapdump.hprof

# Eclipse MAT(Memory Analyzer Tool) 또는 VisualVM으로 분석
# → 어떤 클래스가 힙을 얼마나 차지하는지 트리로 보여줌
```

---

## 전체 실험 흐름 요약

```
실험 1: OOMKilled
  limit 256Mi + MaxRAMPercentage=90% → k6 부하 → OOMKilled 확인
  → limit 512Mi + MaxRAMPercentage=60% → 해결 확인

실험 2: CPU 스로틀링
  UseContainerSupport OFF → 노드 전체 CPU 인식 확인
  → ON + ActiveProcessorCount=2 → 컨테이너 limit 인식 확인

실험 3: GC 로그
  GC 로그 활성화 → k6 부하 → Young GC / Full GC 로그 읽기
  힙을 강제로 작게 → Full GC 발생 → p(95) 응답 지연 확인
  → 적절한 힙 설정으로 해결

실험 4 (선택): Heap Dump
  OOM 발생 시 자동 덤프 → 파일 분석
```

---

## 실험 후 최종 Dockerfile (프로덕션 권장 설정)

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=60.0", \
  "-XX:MinRAMPercentage=50.0", \
  "-XX:ActiveProcessorCount=2", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=3,filesize=10m", \
  "-jar", "app.jar"]
```

| 플래그 | 역할 |
|--------|------|
| `UseContainerSupport` | 컨테이너 limit 인식 (JDK 11+ 기본 ON, 명시적 선언 권장) |
| `MaxRAMPercentage=60` | 힙 한도 = limit × 60% (Non-Heap 여유 확보) |
| `MinRAMPercentage=50` | 힙 최솟값 = limit × 50% (힙이 너무 작게 시작 방지) |
| `ActiveProcessorCount=2` | JVM에게 CPU 2개로 인식시킴 |
| `UseG1GC` | JDK 11+ 기본 GC (명시 권장) |
| `MaxGCPauseMillis=200` | GC pause 목표 200ms |
| `HeapDumpOnOutOfMemoryError` | OOM 시 자동 힙 덤프 |
| `Xlog:gc*` | GC 로그 파일 기록 |
