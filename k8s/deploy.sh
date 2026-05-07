#!/bin/bash
set -e

echo "=== 1. 이미지 빌드 ==="
docker build -t fcfs-claim-app:latest ./backend

echo "=== 2. 네임스페이스 생성 ==="
kubectl apply -f k8s/00-namespace.yaml

echo "=== 3. MySQL + Redis 배포 ==="
kubectl apply -f k8s/01-mysql.yaml
kubectl apply -f k8s/02-redis.yaml

echo "=== MySQL 준비 대기 (최대 60초) ==="
kubectl wait --namespace fcfs \
  --for=condition=ready pod \
  --selector=app=mysql \
  --timeout=60s

echo "=== 4. 앱 배포 ==="
kubectl apply -f k8s/03-app.yaml

echo "=== 5. HPA 적용 ==="
kubectl apply -f k8s/04-hpa.yaml

echo ""
echo "=== 배포 완료 ==="
echo "파드 상태 확인:  kubectl get pods -n fcfs"
echo "HPA 상태 확인:   kubectl get hpa -n fcfs"
echo "부하 테스트:     k6 run k6/02_claim_race.js"
