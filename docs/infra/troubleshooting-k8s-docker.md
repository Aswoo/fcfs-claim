# Kubernetes / Docker 트러블슈팅 기록

로컬 K8s 실험 환경(Docker Desktop) 구축 과정에서 겪은 버그와 해결 과정을 정리한 문서.

---

## 1. JAVA_OPTS가 JVM 프로세스에 반영되지 않음

### 증상
K8s Deployment YAML에 `JAVA_OPTS` 환경변수를 설정했지만, 실제 Java 프로세스에 플래그가 없었다.

```bash
# 환경변수 확인 → 설정됨
kubectl exec -n fcfs <pod> -- env | grep JAVA_OPTS
# JAVA_OPTS=-Xms64m -Xmx160m -Xlog:gc*:file=/tmp/gc.log...

# 실제 프로세스 확인 → 플래그 없음
kubectl exec -n fcfs <pod> -- cat /proc/1/cmdline | tr '\0' ' '
# java -jar app.jar   ← JAVA_OPTS 전혀 없음
```

### 원인
K8s Pod가 **구 버전 이미지**를 사용 중이었다.
구 이미지의 Dockerfile ENTRYPOINT가 `["java", "-jar", "app.jar"]`로, JAVA_OPTS를 아예 참조하지 않았다.
현재 Dockerfile은 `["sh", "-c", "java $JAVA_OPTS -jar app.jar"]`로 수정되어 있었지만, K8s는 이를 반영하지 않았다.

### 접근 과정
```bash
# 1. docker inspect로 현재 로컬 이미지 ENTRYPOINT 확인 → sh -c 방식 맞음
docker inspect fcfs-claim-app:latest --format '{{json .Config.Entrypoint}}'

# 2. Pod가 사용 중인 이미지 SHA와 로컬 이미지 SHA 비교
kubectl get pod <pod> -o jsonpath='{.status.containerStatuses[0].imageID}'
docker inspect fcfs-claim-app:latest --format '{{.Id}}'
# → SHA가 다름! K8s는 구 이미지를 사용 중
```

### 원인 심화: Docker Desktop 이미지 스토어 분리
Docker Desktop은 기본값(overlay2)에서 **Docker 데몬과 K8s containerd가 별도 이미지 스토어**를 사용한다.

```
docker build → Docker 데몬 이미지 스토어 (overlay2)
K8s Pod 실행 → containerd k8s.io 네임스페이스 이미지 스토어
```

`docker build`로 새 이미지를 만들어도 K8s는 containerd에 캐시된 구 이미지를 계속 사용한다.
`imagePullPolicy: Never` 설정이 "캐시에 있으면 그냥 써라"를 의미하기 때문에 더 고착된다.

### 해결
**Docker Desktop 설정 변경으로 같은 스토어를 공유하게 만든다.**

```
Docker Desktop → Settings → General
→ "Use containerd for pulling and storing images" 체크
→ Apply & Restart
```

변경 전후 Storage Driver 확인:
```bash
docker info | grep "Storage Driver"
# 변경 전: overlay2
# 변경 후: overlayfs  ← 같은 containerd 사용
```

이후 `docker build`가 즉시 K8s에 반영된다.

---

## 2. gc.log 파일 미생성 / GC 통계 0회

### 증상
`./run.sh gc-stress` 실행 후 GC 통계가 전부 0회로 출력됐다.

```
[Young GC]  횟수: 0 회
[Full GC]   횟수: 0 회
```

Pod 내부를 확인해도 `/tmp/gc.log` 파일 자체가 없었다.

### 원인 1: -Xmx64m이 Spring Boot 기동 최소 요구량 미달
`03-gc-stress.yaml`에 `-Xmx64m`을 설정했는데, Spring Boot 기동에 최소 150MB 이상의 힙이 필요하다.
결과적으로 JVM이 기동 중 `java.lang.OutOfMemoryError: Java heap space`로 사망하고,
컨테이너가 재시작될 때 `/tmp`가 초기화되어 gc.log가 사라졌다.

### 원인 2: JAVA_OPTS 자체가 JVM에 전달되지 않음
위 1번 이슈(Docker Desktop 이미지 스토어 분리)로 인해 `-Xlog:gc*` 플래그 자체가 적용되지 않았다.

### 접근 과정
```bash
# /tmp 디렉토리 확인 → gc.log 없음
kubectl exec -n fcfs <pod> -- ls /tmp/

# 실제 JVM 프로세스 확인 → JAVA_OPTS 없음
kubectl exec -n fcfs <pod> -- cat /proc/1/cmdline | tr '\0' ' '
# java -jar app.jar

# K8s 환경변수 내 JAVA_OPTS 확인 → 설정은 돼있음
kubectl exec -n fcfs <pod> -- env | grep JAVA_OPTS
# JAVA_OPTS=-Xms64m -Xmx160m -Xlog:gc*:...
```

두 이슈가 겹쳐있어 원인을 순서대로 추적해야 했다.

### 해결
1. `-Xmx64m` → `-Xmx160m`으로 수정 (Spring Boot 기동 가능한 최소 힙)
2. Docker Desktop containerd 이미지 스토어 활성화 (1번 이슈 해결)

수정 후 GC 통계 정상 출력:
```
[Young GC]  횟수: 29회  평균: 6.2ms
[Full GC]   횟수: 8회   평균: 44ms
```

---

## 3. OOMKilled가 k6 부하 없이 기동 중에 발생

### 증상
`./run.sh oom-bad`를 실행하고 `oom-watch`를 켜놓으니,
k6가 시작되기 전 **파드 준비 대기 중**에 OOMKilled가 찍혔다.
이후 `show_result_oom`은 타이밍에 따라 "버텼습니다"를 출력했다.

### 원인
`01-oom-bad.yaml`의 설정이 극단적으로 타이트했다.

```
컨테이너 메모리 limit: 256Mi
-XX:MaxRAMPercentage=90% → 힙 최대: 230MB
Non-Heap (Metaspace, Code Cache 등): ~200MB
합계: 430MB > 256Mi → 기동 중에 이미 초과
```

Spring Boot는 기동 시 JPA, Redis, MySQL 연결, Bean 로딩 등을 수행하며
힙+논힙을 합쳐 256Mi를 넘겨버렸다. 부하가 없어도 죽는 구조였다.

### 의미
이 케이스는 실제 운영에서도 발생한다.
메모리 limit이 너무 작으면 **배포(Rolling Update) 시 새 파드가 뜨자마자 죽어**
서비스 장애로 이어진다. 실험이 의도보다 더 극단적인 케이스를 재현한 셈이다.

### 확인 방법
```bash
# RESTARTS가 1 이상이면 기동 중 사망
kubectl get pod -n fcfs

# Last State에 OOMKilled가 찍혀있으면 확인
kubectl describe pod -n fcfs <pod> | grep -A5 "Last State"
```

---

## 4. imagePullPolicy 전략 실패 시도들

### 시도 1: `imagePullPolicy: IfNotPresent` + 타임스탬프 태그

```yaml
image: fcfs-claim-app:dev-20260508114611
imagePullPolicy: IfNotPresent
```

새 태그를 K8s가 모르니까 Docker Hub에서 당겨오려고 했고, 당연히 실패했다.

```
Failed to pull image: pull access denied, repository does not exist
```

로컬 전용 이미지는 퍼블릭 레지스트리에 없다.

### 시도 2: nsenter로 containerd k8s.io에 직접 이미지 로드

Docker Desktop VM의 containerd에 직접 접근해서 이미지를 임포트하려 했다.

```bash
docker run --rm --privileged --pid=host alpine \
  nsenter -t 1 -m -- /usr/bin/crictl images
```

`/usr/bin/crictl`을 찾았지만 ELF 인터프리터(동적 링커) 불일치로 실행 불가였다.
Docker Desktop VM 바이너리를 alpine 컨테이너 환경에서 직접 실행할 수 없었다.

### 근본 해결
모든 우회책보다 **Docker Desktop 설정 하나 변경**이 정답이었다.
이미지 스토어를 통합하면 `imagePullPolicy: Never`도 정상 동작한다.

---

## 5. Docker Desktop 재시작 시 K8s 클러스터 초기화

### 증상
Docker Desktop을 재시작(containerd 설정 변경 후)하니
Namespace, Deployment, Pod 등 K8s 리소스가 전부 사라졌다.

```bash
kubectl get namespace fcfs
# Error from server (NotFound): namespaces "fcfs" not found
```

### 원인
Docker Desktop의 로컬 K8s는 **휘발성 클러스터**다.
재시작 시 etcd 데이터가 초기화되는 경우가 있다.

### 해결
전체 재배포 순서가 중요하다:

```bash
# 1. Namespace 먼저
kubectl apply -f k8s/00-namespace.yaml

# 2. 의존성 없는 것부터 (MySQL, Redis)
kubectl apply -f k8s/01-mysql.yaml
kubectl apply -f k8s/02-redis.yaml

# 3. MySQL 준비 완료 대기 후 App 배포
kubectl wait --for=condition=ready pod -l app=mysql -n fcfs --timeout=120s
kubectl apply -f k8s/03-app.yaml
```

MySQL이 준비되기 전에 App이 뜨면 DB 연결 실패로 재시작을 반복한다.

또한 재시작 직후 `mysql:8.0` 같은 이미지가 containerd에 없어 `ImagePullBackOff`가 발생할 수 있다.
이 경우 먼저 `docker pull`로 가져오면 된다.

```bash
docker pull mysql:8.0
docker pull redis:7.0-alpine
```

---

## 정리: Docker Desktop K8s 로컬 개발 시 핵심 설정

| 항목 | 권장 설정 |
|---|---|
| 이미지 스토어 | containerd 통합 (`overlayfs`) |
| imagePullPolicy | `Never` (이미지 스토어 통합 후) |
| 이미지 빌드 | `docker build` 후 바로 K8s에 반영됨 |
| 클러스터 재시작 시 | 재배포 스크립트(`k8s/deploy.sh`) 활용 |

### 대안: Kind 사용 시

Kind는 이미지 로딩 명령어를 공식 지원한다.

```bash
kind create cluster --name fcfs
kind load docker-image fcfs-claim-app:latest --name fcfs
```

Docker Desktop보다 이미지 반영이 명시적이어서 혼선이 없다.
단, 기존 클러스터 전체를 다시 구성해야 하는 전환 비용이 있다.
