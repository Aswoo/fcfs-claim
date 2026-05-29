# 로컬 k8s → AWS EC2 k3s 마이그레이션 가이드

## 구성 변경 요약

| 항목 | 로컬 (Docker Desktop) | AWS EC2 (k3s) |
|---|---|---|
| K8s 엔진 | Docker Desktop 내장 k8s | k3s |
| 이미지 저장소 | 로컬 moby 네임스페이스 | ECR |
| 이미지 pull 방식 | `IfNotPresent` (브리지) | `Always` + imagePullSecret |
| 외부 접근 | `localhost:8081` | EC2 퍼블릭 IP:8081 |
| 이미지 아키텍처 | ARM64 (Apple Silicon) | amd64 (EC2 x86) |

---

## 사전 준비

- AWS CLI 설치 및 자격증명 설정 (`aws configure`)
- Docker Desktop 실행 중
- pem 키파일 (`~/.ssh/fcfs-key.pem`)

---

## Step 1 — AWS 인프라 세팅

### 키페어 생성
```bash
aws ec2 create-key-pair \
  --key-name fcfs-key \
  --query 'KeyMaterial' \
  --output text > ~/.ssh/fcfs-key.pem
chmod 400 ~/.ssh/fcfs-key.pem
```

### 보안그룹 생성 및 포트 오픈
```bash
SG_ID=$(aws ec2 create-security-group \
  --group-name fcfs-sg \
  --description "fcfs security group" \
  --query 'GroupId' --output text)

aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 22 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 8081 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-id $SG_ID --protocol tcp --port 6443 --cidr 0.0.0.0/0
```

### EC2 인스턴스 생성
```bash
# Ubuntu 22.04 AMI (ap-northeast-2)
AMI_ID=$(aws ec2 describe-images \
  --owners amazon \
  --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" \
            "Name=state,Values=available" \
  --query 'sort_by(Images, &CreationDate)[-1].ImageId' \
  --output text)

INSTANCE_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type c7i-flex.large \
  --key-name fcfs-key \
  --security-group-ids $SG_ID \
  --count 1 \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=fcfs-server}]' \
  --query 'Instances[0].InstanceId' \
  --output text)

# IP 확인
aws ec2 wait instance-running --instance-ids $INSTANCE_ID
EC2_IP=$(aws ec2 describe-instances \
  --instance-ids $INSTANCE_ID \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)
echo "EC2 IP: $EC2_IP"
```

---

## Step 2 — k3s 설치

```bash
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "curl -sfL https://get.k3s.io | sh -"

# 확인
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "sudo kubectl get nodes"
```

---

## Step 3 — ECR 이미지 빌드 및 Push

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${ACCOUNT_ID}.dkr.ecr.ap-northeast-2.amazonaws.com/fcfs-claim-app"

# ECR 레포 생성
aws ecr create-repository --repository-name fcfs-claim-app --region ap-northeast-2

# ECR 로그인
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.ap-northeast-2.amazonaws.com

# amd64로 빌드 및 push (Mac Apple Silicon 주의)
docker buildx build \
  --platform linux/amd64 \
  -t ${ECR_URI}:latest \
  --push \
  ./backend
```

> **주의**: Apple Silicon Mac에서는 반드시 `--platform linux/amd64` 옵션 필요.  
> 없으면 EC2에서 `no match for platform in manifest` 에러 발생.

---

## Step 4 — k8s yaml 수정

`k8s/03-app.yaml` 변경:

```yaml
# Before (로컬)
image: docker.io/library/fcfs-claim-app:latest
imagePullPolicy: IfNotPresent

# After (EC2)
image: {ACCOUNT_ID}.dkr.ecr.ap-northeast-2.amazonaws.com/fcfs-claim-app:latest
imagePullPolicy: Always
```

`imagePullSecrets` 추가:
```yaml
spec:
  imagePullSecrets:
    - name: ecr-secret
  containers:
    - name: fcfs-app
```

---

## Step 5 — ECR 인증 시크릿 등록 및 배포

```bash
# ECR 시크릿 생성
ECR_TOKEN=$(aws ecr get-login-password --region ap-northeast-2)
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "
sudo kubectl apply -f 00-namespace.yaml
sudo kubectl create secret docker-registry ecr-secret \
  --namespace fcfs \
  --docker-server=${ACCOUNT_ID}.dkr.ecr.ap-northeast-2.amazonaws.com \
  --docker-username=AWS \
  --docker-password='${ECR_TOKEN}' \
  --dry-run=client -o yaml | sudo kubectl apply -f -
"

# yaml 파일 복사 및 배포
scp -i ~/.ssh/fcfs-key.pem k8s/*.yaml ubuntu@$EC2_IP:~/
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "
sudo kubectl apply -f 01-mysql.yaml
sudo kubectl apply -f 02-redis.yaml
sudo kubectl apply -f 03-app.yaml
sudo kubectl apply -f 04-hpa.yaml
"
```

---

## Step 6 — metrics-server 설치 (HPA 필수)

```bash
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "
sudo kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
sudo kubectl patch deployment metrics-server -n kube-system \
  --type='json' \
  -p='[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--kubelet-insecure-tls\"}]'
"

# HPA 확인 (cpu: X%/50% 로 표시돼야 정상)
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP "sudo kubectl get hpa -n fcfs"
```

---

## Step 7 — k6 부하 테스트

```bash
# API 동작 확인
curl http://$EC2_IP:8081/api/v1/events/1/status

# k6 실행 (BASE_URL을 EC2로 변경)
k6 run -e BASE_URL=http://$EC2_IP:8081 k6/01_queue_stress.js
```

별도 터미널에서 HPA 모니터링:
```bash
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP \
  "watch -n 2 'sudo kubectl get hpa,pods -n fcfs'"
```

---

## 코드 변경 후 재배포

```bash
# 1. amd64 이미지 재빌드 및 push
docker buildx build --platform linux/amd64 -t ${ECR_URI}:latest --push ./backend

# 2. 파드 재시작 (새 이미지 반영)
ssh -i ~/.ssh/fcfs-key.pem ubuntu@$EC2_IP \
  "sudo kubectl rollout restart deployment/fcfs-app -n fcfs"
```

---

## 사용 후 EC2 중지 (과금 방지)

```bash
# 중지 (재시작 가능, 데이터 유지)
aws ec2 stop-instances --instance-ids $INSTANCE_ID

# 완전 삭제
aws ec2 terminate-instances --instance-ids $INSTANCE_ID
```

---

## 트러블슈팅

| 에러 | 원인 | 해결 |
|---|---|---|
| `no match for platform` | ARM 이미지를 amd64 EC2에서 실행 | `--platform linux/amd64` 옵션 추가 |
| `ErrImagePull` | ECR 인증 안 됨 | `ecr-secret` 시크릿 재생성 |
| HPA `unknown/50%` | metrics-server 미설치 | Step 6 실행 |
| SSH `Permission denied` | pem 파일 비어있음 | 키페어 삭제 후 재생성 |
