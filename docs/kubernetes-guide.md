# Kubernetes 적용 가이드

> 이 프로젝트에서 Kubernetes를 왜 쓰는지, 어떤 파일이 무슨 역할인지, 실제로 어떤 문제를 만났고 어떻게 해결했는지를 처음부터 설명합니다.

---

## 1. 왜 Kubernetes를 썼나?

### Docker Compose로는 부족한 것

이 프로젝트 이전에 Docker Compose로 서버를 구성했습니다.

```
Docker Compose 구조:
  app (Spring Boot) × 2개 (app1, app2 고정)
  nginx (로드밸런서)
  mysql
  redis
```

문제는 **인스턴스 수를 손으로 정해야 한다**는 것입니다.
- 평소엔 2개가 과한데, 선착순 이벤트 시작 순간엔 2개도 부족합니다.
- `app1`, `app2`처럼 이름을 직접 써넣으면 동적으로 추가/제거가 불편합니다.

### Kubernetes가 해결하는 것

```
Kubernetes (K8s) 구조:
  fcfs-app Deployment (시작 1개 → 트래픽에 따라 자동으로 최대 5개)
  HPA (HorizontalPodAutoscaler) - CPU 50% 넘으면 파드 추가
  mysql
  redis
```

**핵심 차이: 자동 스케일링**
- CPU 사용률이 설정한 임계값을 넘으면 K8s가 알아서 인스턴스를 추가합니다.
- 부하가 줄면 알아서 줄입니다.
- 개발자가 개입하지 않아도 됩니다.

---

## 2. 사용한 환경: Docker Desktop K8s

### 왜 Docker Desktop K8s인가?

Kubernetes는 보통 AWS EKS, GKE, AKS 같은 클라우드 서비스에서 씁니다. 하지만 이것들은 비용이 발생합니다. 개발/학습 목적으로 무료로 쓸 수 있는 방법들이 있습니다.

| 옵션 | 특징 |
|------|------|
| Docker Desktop K8s | 이미 Docker Desktop이 있으면 설정 클릭 한 번으로 활성화 |
| minikube | 별도 설치, 가상머신 안에서 실행 |
| kind | Docker 컨테이너 안에서 K8s 실행 |

이 프로젝트에선 **Docker Desktop K8s**를 선택했습니다.
Docker Desktop Settings → Kubernetes → Enable Kubernetes 체크만 하면 됩니다.

---

## 3. 파일 구조 설명

```
k8s/
├── 00-namespace.yaml    # 격리 공간 선언
├── 01-mysql.yaml        # MySQL 배포
├── 02-redis.yaml        # Redis 배포
├── 03-app.yaml          # Spring Boot 앱 배포 + 서비스
├── 04-hpa.yaml          # 자동 스케일링 설정
└── deploy.sh            # 위 파일들을 순서대로 적용하는 스크립트
```

### 00-namespace.yaml — 격리 공간

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: fcfs
```

**Namespace란?**
K8s 클러스터 안에서 논리적으로 분리된 공간입니다. 같은 클러스터를 여러 팀/프로젝트가 쓸 때 서로 간섭하지 않도록 분리합니다.

비유: 회사 건물(클러스터) 안의 층(네임스페이스). 3층(fcfs)에는 이 프로젝트의 모든 것이 들어갑니다.

이후 모든 파일에 `namespace: fcfs`를 붙이면 이 공간에 배치됩니다.

---

### 01-mysql.yaml — MySQL 배포

```yaml
apiVersion: apps/v1
kind: Deployment       # "이 컨테이너를 실행하고 유지해줘"
metadata:
  name: mysql
  namespace: fcfs
spec:
  replicas: 1          # MySQL은 1개만
  selector:
    matchLabels:
      app: mysql       # 아래 template과 연결하는 라벨
  template:
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          env:
            - name: MYSQL_DATABASE
              value: fcfs_claim
            - name: MYSQL_ROOT_PASSWORD
              value: password
          readinessProbe:           # MySQL이 완전히 뜰 때까지 트래픽 안 보냄
            exec:
              command: ["mysqladmin", "ping", "-h", "localhost"]
            initialDelaySeconds: 20
---
apiVersion: v1
kind: Service          # "이 컨테이너를 다른 파드가 찾을 수 있게 해줘"
metadata:
  name: mysql
  namespace: fcfs
spec:
  selector:
    app: mysql
  ports:
    - port: 3306
```

**Deployment란?**
"이 이미지로 컨테이너를 N개 실행하고, 죽으면 다시 살려줘"라고 K8s에게 선언하는 것입니다.

**Service란?**
K8s 안에서 컨테이너들은 IP가 계속 바뀝니다 (파드가 죽고 다시 뜨면). Service를 만들면 `mysql`이라는 고정된 DNS 이름으로 접근할 수 있습니다.

앱 설정에 `spring.datasource.url: jdbc:mysql://mysql:3306/fcfs_claim`이라고 쓸 수 있는 이유가 이것입니다.

**readinessProbe란?**
"이 컨테이너가 실제로 요청을 받을 준비가 됐는지 확인해줘"입니다. MySQL은 컨테이너가 뜬다고 바로 쓸 수 있는 게 아니고 초기화에 20초쯤 걸립니다. readinessProbe가 성공하기 전까지 트래픽을 보내지 않습니다.

---

### 02-redis.yaml — Redis 배포

```yaml
containers:
  - name: redis
    image: redis:7.0-alpine
    command: ["redis-server", "--maxmemory", "256mb"]
```

MySQL과 구조가 같습니다. `--maxmemory 256mb`는 Redis가 메모리를 무한정 쓰지 못하도록 제한하는 옵션입니다.

Service 이름이 `redis`로 선언되어 있으므로, 앱 설정에서 `spring.data.redis.host: redis`로 접근할 수 있습니다.

---

### 03-app.yaml — Spring Boot 앱 배포

이 파일이 가장 중요합니다.

```yaml
spec:
  replicas: 1          # 처음엔 1개, HPA가 필요 시 자동으로 늘림

  containers:
    - name: fcfs-app
      image: docker.io/library/fcfs-claim-app:latest
      imagePullPolicy: Never    # 외부에서 다운받지 말고, 로컬에 있는 이미지 사용
      
      env:
        - name: SPRING_PROFILES_ACTIVE
          value: docker           # application-docker.yml 설정 사용
        - name: DB_USERNAME
          value: root
        - name: DB_PASSWORD
          value: password
      
      ports:
        - containerPort: 8081
      
      resources:
        requests:
          cpu: "500m"       # 이 파드에 0.5 코어를 보장해줘
          memory: "512Mi"
        limits:
          cpu: "1000m"      # 최대 1 코어까지만 쓸 수 있어
          memory: "1Gi"
      
      readinessProbe:
        httpGet:
          path: /api/v1/events/1/products
          port: 8081
        initialDelaySeconds: 30   # Spring Boot 뜨는 데 30초 여유
        periodSeconds: 10
```

**resources.requests / limits가 왜 중요한가?**

HPA(자동 스케일링)가 동작하려면 반드시 `requests.cpu`가 있어야 합니다.

HPA가 CPU 사용률을 계산하는 방식:
```
CPU 사용률 = 실제 사용하는 CPU / requests.cpu
           = 300m / 500m
           = 60%
```

requests 없으면 분모가 0이라 계산이 불가능합니다.

**Service (LoadBalancer 타입)**

```yaml
apiVersion: v1
kind: Service
spec:
  type: LoadBalancer    # 외부(로컬 호스트)에서 접근 가능하게 함
  ports:
    - port: 8081
      targetPort: 8081
```

Docker Desktop K8s에서 LoadBalancer 타입 Service를 만들면 `localhost:8081`로 접근할 수 있습니다.

Service 타입 3가지 비교:
| 타입 | 설명 |
|------|------|
| ClusterIP | K8s 클러스터 내부에서만 접근 가능 (MySQL, Redis처럼) |
| NodePort | 노드 IP + 랜덤 포트로 외부 접근 가능 |
| LoadBalancer | 외부 로드밸런서(클라우드/로컬) 통해 단일 IP로 접근 |

---

### 04-hpa.yaml — 자동 스케일링

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: fcfs-app-hpa
  namespace: fcfs
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: fcfs-app          # 어떤 Deployment를 스케일할지

  minReplicas: 1            # 부하 없을 때 최소 1개 유지
  maxReplicas: 5            # 최대 5개까지 자동 확장

  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50  # 평균 CPU 50% 초과 시 파드 추가

  behavior:
    scaleUp:
      stabilizationWindowSeconds: 15  # 15초 연속 초과해야 스케일업
    scaleDown:
      stabilizationWindowSeconds: 60  # 60초 연속 여유 있어야 스케일다운
```

**HPA가 동작하는 흐름:**

```
1. metrics-server가 10초마다 각 파드의 CPU 사용량을 수집
2. HPA가 수집된 값으로 사용률 계산
3. 사용률 > 50% → "파드를 몇 개로 늘려야 하나?" 계산
   필요 파드 수 = ceil(현재 파드 수 × 현재 사용률 / 목표 사용률)
   예: ceil(1 × 80% / 50%) = ceil(1.6) = 2개
4. 15초 동안 연속으로 초과 상태면 스케일업 실행
5. 파드가 2개로 늘어나면 부하가 분산되어 사용률이 낮아짐
```

**stabilizationWindow가 필요한 이유:**

없으면 CPU가 잠깐 튀어도 바로 파드를 늘렸다가 줄였다가를 반복합니다 (flapping). 15초 창을 두어 일시적인 스파이크에는 반응하지 않습니다.

---

## 4. metrics-server 설치

HPA가 CPU 사용량을 알려면 **metrics-server**가 필요합니다. Docker Desktop K8s에는 기본으로 없어서 직접 설치했습니다.

```bash
# 설치
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# Docker Desktop K8s 특성상 TLS 인증서 검증 비활성화 필요
kubectl patch deployment metrics-server \
  -n kube-system \
  --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

**왜 --kubelet-insecure-tls가 필요한가?**

metrics-server는 각 노드의 kubelet에서 메트릭을 가져옵니다. 이때 TLS 인증서로 통신하는데, Docker Desktop K8s의 자체 인증서는 신뢰할 수 없다고 판단해서 통신이 실패합니다. 개발 환경에서만 이 옵션으로 우회합니다. (운영 환경에선 올바른 인증서를 발급해야 합니다.)

설치 확인:
```bash
kubectl top pods -n fcfs    # CPU/메모리 사용량 표시되면 정상
```

---

## 5. 가장 큰 문제: ErrImageNeverPull

### 문제 상황

```
파드 상태: ErrImageNeverPull
```

`imagePullPolicy: Never`로 설정했더니 이미지를 못 찾는다는 오류가 났습니다.

### 원인

Docker Desktop K8s의 내부 구조 때문입니다.

```
Mac 호스트
└── Docker Desktop (Linux VM 내부)
    ├── Docker daemon (도커 컨테이너 실행)
    │   └── containerd (이미지 저장소, namespace: "moby")
    └── Kubernetes
        └── containerd (이미지 저장소, namespace: "k8s.io")  ← 별개!
```

`docker build`로 만든 이미지는 Docker daemon의 `moby` 네임스페이스에 저장됩니다.
K8s는 `k8s.io` 네임스페이스에서만 이미지를 찾습니다.
두 저장소는 서로 공유되지 않습니다.

### 해결 과정

**시도 1: imagePullPolicy: Always + 로컬 레지스트리**

port 5000을 로컬 레지스트리로 쓰려다 이미 사용 중이어서 실패. port 5001로 레지스트리를 띄웠지만, K8s 파드 안에서 `localhost:5001`은 파드 자신을 가리키므로 접근 불가.

**시도 2: kubectl debug node**

```bash
# K8s 노드 안으로 디버그 파드 진입
kubectl debug node/desktop-control-plane --image=alpine -- sleep 300
```

이 명령어는 K8s 노드(Docker Desktop Linux VM) 위에 특수 파드를 띄우고, 노드의 파일시스템을 `/host`에 마운트해줍니다.

노드에 `ctr`이 있다는 걸 확인:
```bash
kubectl exec <debug-pod> -- ls /host/usr/local/bin/
# 출력: ... ctr ...
```

**최종 해결책: ctr로 직접 임포트**

```bash
# 1. Mac에서 이미지를 tar 파일로 저장
docker save fcfs-claim-app:latest -o /tmp/fcfs-app.tar

# 2. tar 파일을 디버그 파드로 복사
kubectl cp /tmp/fcfs-app.tar <debug-pod>:/tmp/fcfs-app.tar

# 3. 디버그 파드에서 K8s containerd의 k8s.io 네임스페이스로 임포트
kubectl exec <debug-pod> -- sh -c \
  "cp /tmp/fcfs-app.tar /host/tmp/fcfs-app.tar && \
   chroot /host /usr/local/bin/ctr \
     --address /run/containerd/containerd.sock \
     -n k8s.io images import /tmp/fcfs-app.tar"
```

**ctr 명령어 설명:**

| 옵션 | 의미 |
|------|------|
| `chroot /host` | 노드의 파일시스템을 루트로 사용 (노드의 바이너리 실행 가능) |
| `ctr` | containerd 커맨드라인 도구 |
| `--address /run/containerd/containerd.sock` | containerd 소켓 경로 |
| `-n k8s.io` | K8s가 쓰는 네임스페이스 |
| `images import` | tar 파일에서 이미지 임포트 |

이후 매니페스트를 수정했습니다:
```yaml
image: docker.io/library/fcfs-claim-app:latest
imagePullPolicy: Never   # 로컬(k8s.io 네임스페이스)에서 찾아라
```

---

## 6. 실제 실행 방법

### 최초 배포 (순서 중요)

```bash
# 1. 이미지 빌드
docker build -t fcfs-claim-app:latest ./backend

# 2. 이미지를 K8s containerd로 임포트 (위 과정을 스크립트화)
docker save fcfs-claim-app:latest -o /tmp/fcfs-app.tar
DEBUG_POD=$(kubectl debug node/desktop-control-plane --image=alpine -- sleep 60 \
  2>&1 | grep "Creating debugging pod" | awk '{print $3}')
kubectl wait --for=condition=Ready pod/$DEBUG_POD --timeout=30s
kubectl cp /tmp/fcfs-app.tar $DEBUG_POD:/tmp/fcfs-app.tar
kubectl exec $DEBUG_POD -- sh -c \
  "cp /tmp/fcfs-app.tar /host/tmp/ && \
   chroot /host /usr/local/bin/ctr --address /run/containerd/containerd.sock \
   -n k8s.io images import /tmp/fcfs-app.tar"

# 3. K8s 리소스 배포
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-mysql.yaml
kubectl apply -f k8s/02-redis.yaml
kubectl wait --namespace fcfs --for=condition=ready pod --selector=app=mysql --timeout=60s
kubectl apply -f k8s/03-app.yaml
kubectl apply -f k8s/04-hpa.yaml
```

또는 `k8s/deploy.sh`를 실행합니다 (이미지 임포트 전제):
```bash
bash k8s/deploy.sh
```

### 상태 확인 명령어

```bash
# 파드 상태 확인
kubectl get pods -n fcfs

# HPA 상태 확인 (CPU 사용률, 현재 레플리카 수)
kubectl get hpa -n fcfs

# CPU/메모리 실시간 확인
kubectl top pods -n fcfs

# 파드 로그 확인
kubectl logs -n fcfs <파드이름> -f

# 서비스 확인
kubectl get svc -n fcfs
```

---

## 7. HPA 스케일업 실제 확인

k6 부하 테스트를 돌리면서 관찰한 결과입니다.

```
체크 1:  CPU: 11m/500m = 2%  →  파드 1개  (HPA: 1%/5%)
체크 4:  CPU: 16m/500m = 3%  →  파드 1개  (HPA: 3%/5%)
체크 8:  CPU: 46m/500m = 9%  →  파드 1→2개!  (HPA: 9%/5%, 임계값 초과!)
체크 9:  신규 파드 JVM 기동 중 CPU 975m 급등 (JIT 컴파일 때문, 정상)
체크 12: 부하 분산 완료, 안정화
```

**새 파드 뜰 때 CPU가 갑자기 급등하는 이유:**

JVM(Java)은 처음 실행 시 클래스 로딩, JIT 컴파일 등 초기화 작업이 집중되어 CPU를 많이 씁니다. 30초 정도 지나면 안정됩니다. 이 때문에 `readinessProbe`에 `initialDelaySeconds: 30`을 줬습니다. readinessProbe가 통과하기 전까지 이 파드로 트래픽이 오지 않습니다.

---

## 8. Docker Compose와 K8s 비교 정리

| 항목 | Docker Compose | Kubernetes |
|------|---------------|------------|
| 스케일링 | 수동 (`--scale app=3`) | 자동 (HPA) |
| 서비스 디스커버리 | Docker DNS | K8s Service |
| 로드밸런싱 | Nginx 별도 설정 | Service가 자동으로 |
| 헬스체크 | 없거나 Dockerfile에 | readinessProbe / livenessProbe |
| 배포 관리 | docker-compose.yml | Deployment YAML |
| 학습 곡선 | 낮음 | 높음 |
| 운영 수준 | 단순 서비스에 적합 | 대규모 프로덕션 |

이 프로젝트에서는 선착순 이벤트처럼 **순간적으로 트래픽이 폭증했다가 줄어드는 패턴**에 K8s HPA가 매우 적합합니다.
