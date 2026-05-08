# JVM OOMKilled 실험 — 로그 읽기 가이드

실제 실험에서 수집한 로그를 기반으로 작성했다.  
"서버가 갑자기 죽었는데 로그에 아무것도 없다"는 상황을 처음 마주쳤을 때 어디서 무엇을 봐야 하는지를 다룬다.

---

## 실험 설정

```
memory limit : 256Mi
JAVA_OPTS    : -XX:MaxRAMPercentage=90.0
```

왜 이 조합이 문제인지:

```
256Mi × 90% = 230Mi   ← JVM이 Heap에 쓰려는 크기

JVM 실제 메모리:
  Heap       230Mi
  Metaspace  ~100Mi   (Spring Boot 클래스 로딩)
  Threads     ~50Mi   (Tomcat 스레드)
  Code Cache  ~50Mi   (JIT 컴파일 결과)
  ────────────────
  합계        ~430Mi   ← limit(256Mi)의 1.7배 → OOMKilled 확정
```

---

## Step 1 — 서버가 갑자기 죽었다. 로그를 본다.

```bash
kubectl logs -n fcfs <파드이름> --previous
```

```
Started FcfsClaimApplication in 7.4 seconds
이벤트 복구 완료
Initializing Spring DispatcherServlet 'dispatcherServlet'
Completed initialization in 28 ms
← 여기서 로그가 끊긴다. 에러 메시지가 없다.
```

**에러 메시지가 없는 게 이상하다.**

Java `OutOfMemoryError`였다면 이렇게 남는다:
```
java.lang.OutOfMemoryError: Java heap space
    at java.util.Arrays.copyOf(Arrays.java:3210)
    at ...
```

OOMKilled는 스택 트레이스가 없다. JVM이 예외를 던진 게 아니라 **OS가 프로세스를 강제로 죽였기 때문이다.** 로그만 보면 정상 실행 중에 갑자기 사라진 것처럼 보인다. 이게 OOMKilled를 처음 접하는 개발자가 원인을 못 찾는 이유다.

> **로그에 에러가 없는데 서버가 죽었다면 → OOMKilled를 먼저 의심한다.**

---

## Step 2 — 원인 확인: describe

```bash
kubectl describe pod -n fcfs <파드이름> | grep -A 6 "Last State"
```

```
Last State:     Terminated
  Reason:       OOMKilled    ← 원인
  Exit Code:    137          ← 128 + SIGKILL(9)
  Started:      16:21:25
  Finished:     16:28:08     ← 약 7분 실행 후 사망
Restart Count:  1
```

**Exit Code 137의 의미:**

```
137 = 128 + 9 (SIGKILL)
```

OS가 SIGKILL 시그널을 보내 강제 종료했다는 뜻이다.  
프로세스가 스스로 죽은 게 아니라 외부(OS cgroup)에서 죽인 것이다.

| Exit Code | 의미 |
|-----------|------|
| 0 | 정상 종료 |
| 1 | 앱 내부 오류 (예외, 설정 오류) |
| 137 | SIGKILL — OS가 강제 종료 (OOMKilled, `kill -9`) |
| 143 | SIGTERM — 정상적인 종료 요청 (배포, 스케일 다운) |

---

## Step 3 — 설정값 확인: 왜 죽었는지

```bash
kubectl describe pod -n fcfs <파드이름> | grep -A 4 "Limits"
kubectl describe pod -n fcfs <파드이름> | grep "JAVA_OPTS" -A 1
```

```
Limits:
  cpu:     1
  memory:  256Mi         ← 컨테이너에 허용된 메모리 한도
JAVA_OPTS: -XX:MaxRAMPercentage=90.0
```

256Mi의 90%는 230Mi다. 그런데 JVM은 Heap 외에도 Metaspace, Thread Stack, Code Cache를 써야 한다. 이 Non-Heap 영역만 합쳐도 ~200Mi다. 230Mi + 200Mi = 430Mi로 limit의 1.7배가 된다.

---

## Step 4 — 부하 없이도 이미 한계: top

```bash
kubectl top pod -n fcfs
```

```
NAME                        CPU    MEMORY
fcfs-app-78ff9c7548-jp2kn   10m    246Mi
                                   ↑
                             limit 256Mi 중 96% 사용 중
                             k6 없이 그냥 켜만 놔도 이 수치
```

k6가 요청을 보내면 Heap에 객체가 생성되면서 256Mi를 초과하는 순간 OOMKilled된다.  
**이 수치를 보면 "언제 죽어도 이상하지 않은 상태"라는 걸 바로 알 수 있다.**

> `kubectl top` 에서 메모리가 limit의 80%를 넘으면 위험 신호다.

---

## Step 5 — K8s가 그 사이에 한 일: events

```bash
kubectl get events -n fcfs --sort-by='.lastTimestamp' | tail -15
```

```
Warning  OOMKilled         pod/jp2kn    ← 파드 사망

Normal   SuccessfulRescale hpa          New size: 2; reason: cpu resource utilization above target
                                        ← k6 부하로 CPU가 올라가자 HPA가 파드를 2개로 늘림

Normal   SuccessfulRescale hpa          New size: 1; reason: All metrics below target
                                        ← k6 종료 후 HPA가 다시 1개로 줄임
```

k6가 CPU도 끌어올렸기 때문에 HPA가 파드를 추가로 생성했다. 이 두 번째 파드도 동일한 256Mi 설정이라 부하를 받다가 종료 과정에서 Error가 났다. OOMKilled와는 별개의 HPA 스케일 다운 부수 효과다.

---

## Step 6 — 해결 후 비교

설정 변경:
```
memory limit : 512Mi
JAVA_OPTS    : -XX:MaxRAMPercentage=60.0
```

```bash
kubectl top pod -n fcfs   # 적용 후 확인
```

```
NAME                        CPU    MEMORY
fcfs-app-xxx                10m    246Mi
                                   ↑
                             limit 512Mi 중 48% 사용
                             k6를 돌려도 여유가 있다
```

힙 계산:
```
512Mi × 60% = 307Mi (Heap 한도)
Non-Heap     ≈ 200Mi
─────────────────────
합계          ≈ 507Mi   ← 512Mi 안에 여유 있게 들어옴
```

동일한 k6 부하를 줘도 OOMKilled가 발생하지 않는다.

---

## 전체 흐름 요약

```
1. 서버가 갑자기 죽었다
   └→ kubectl logs --previous
      에러 메시지 없이 로그가 끊긴다 → OOMKilled 의심

2. 원인 확인
   └→ kubectl describe pod | grep "Last State"
      Reason: OOMKilled / Exit Code: 137 → 확정

3. 설정 확인
   └→ kubectl describe pod | grep "Limits\|JAVA_OPTS"
      256Mi limit + MaxRAMPercentage=90% → Heap+NonHeap > limit

4. 현재 압박 수준 확인
   └→ kubectl top pod
      246Mi / 256Mi = 96% → 부하 없이도 한계

5. 해결
   └→ limit을 512Mi로 올리거나 MaxRAMPercentage를 60%로 낮춤
      top에서 메모리 비율이 50% 안팎이면 안전
```

---

## 핵심 명령어 3개

| 상황 | 명령어 | 확인 포인트 |
|------|--------|-------------|
| 서버가 갑자기 죽었을 때 | `kubectl logs --previous` | 에러 없이 끊기면 OOMKilled 의심 |
| 원인 확인 | `kubectl describe pod \| grep -A 6 "Last State"` | `Reason: OOMKilled`, `Exit Code: 137` |
| 현재 메모리 압박 | `kubectl top pod` | limit 대비 80% 넘으면 위험 |

---

## OOMKilled vs OutOfMemoryError 차이

| | OOMKilled | OutOfMemoryError |
|--|--|--|
| 누가 죽이나 | OS (cgroup) | JVM 내부 |
| 로그에 남는가 | **없음** | 스택 트레이스 남음 |
| Exit Code | 137 | 1 |
| 발생 조건 | 컨테이너 limit 초과 | JVM Heap 한도 초과 |
| 확인 방법 | `kubectl describe` Last State | 앱 로그 |
