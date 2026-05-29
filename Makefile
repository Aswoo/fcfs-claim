NAMESPACE  := fcfs
IMAGE_BASE := fcfs-claim-app
IMAGE_TAG  := $(shell date +%Y%m%d%H%M%S)
IMAGE      := $(IMAGE_BASE):$(IMAGE_TAG)

# ── EC2 설정 ──────────────────────────────────────────
EC2_IP     := 3.38.136.109
EC2_KEY    := ~/.ssh/fcfs-key.pem
EC2_USER   := ubuntu
AWS_REGION := ap-northeast-2
AWS_ACCOUNT:= 990597228794
ECR_IMAGE  := $(AWS_ACCOUNT).dkr.ecr.$(AWS_REGION).amazonaws.com/$(IMAGE_BASE):latest

.PHONY: dev deploy redeploy port status logs experiment hooks \
        ec2-up ec2-deploy ec2-redeploy ec2-status ec2-logs ec2-test ec2-down

# ── 초기 세팅 (clone 후 1회) ──────────────────────────
hooks:
	git config core.hooksPath .githooks
	@echo "Git hooks installed. (.githooks/pre-push)"

# ── 개발 ────────────────────────────────────────────
dev:
	./dev.sh

# ── K8s 배포 ─────────────────────────────────────────
deploy:
	./k8s/deploy.sh

redeploy:
	docker build -t $(IMAGE) -t $(IMAGE_BASE):latest ./backend
	echo "$(IMAGE_TAG)" > .image-tag
	kubectl set image deployment/fcfs-app fcfs-app=$(IMAGE) -n $(NAMESPACE)
	kubectl rollout status deployment/fcfs-app -n $(NAMESPACE) --timeout=90s

# ── 포트포워드 ────────────────────────────────────────
port:
	kubectl port-forward --address 0.0.0.0 -n $(NAMESPACE) svc/fcfs-app 8081:8081

# ── 상태 확인 ─────────────────────────────────────────
status:
	kubectl get pods -n $(NAMESPACE)

logs:
	kubectl logs -n $(NAMESPACE) -l app=fcfs-app -f

# ── k6 테스트 ─────────────────────────────────────────
test-local:
	k6 run --out influxdb=http://localhost:8086/k6 k6/01_queue_stress.js

test-ec2:
	k6 run -e BASE_URL=http://$(EC2_IP):8081 --out influxdb=http://localhost:8086/k6 k6/01_queue_stress.js

# ── EC2 명령어 ────────────────────────────────────────
ec2-up:
	@echo "=== EC2 인스턴스 생성 ==="
	$(eval INSTANCE_ID=$(shell aws ec2 run-instances \
		--image-id ami-0596f7562954deb8e \
		--instance-type c7i-flex.large \
		--key-name fcfs-key \
		--security-group-ids sg-041502e59716acfa6 \
		--count 1 \
		--tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=fcfs-server}]' \
		--query 'Instances[0].InstanceId' --output text))
	@echo "인스턴스 ID: $(INSTANCE_ID)"
	aws ec2 wait instance-running --instance-ids $(INSTANCE_ID)
	@echo "퍼블릭 IP: $$(aws ec2 describe-instances --instance-ids $(INSTANCE_ID) --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
	@echo "EC2_IP를 Makefile에 업데이트하세요"

ec2-deploy:
	@echo "=== ECR 이미지 빌드 & Push ==="
	aws ecr get-login-password --region $(AWS_REGION) | docker login --username AWS --password-stdin $(AWS_ACCOUNT).dkr.ecr.$(AWS_REGION).amazonaws.com
	docker buildx build --platform linux/amd64 -t $(ECR_IMAGE) --push ./backend
	@echo "=== k3s 설치 ==="
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) "curl -sfL https://get.k3s.io | sh -"
	@echo "=== ECR 시크릿 등록 ==="
	$(eval ECR_TOKEN=$(shell aws ecr get-login-password --region $(AWS_REGION)))
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) "\
		sudo kubectl apply -f 00-namespace.yaml 2>/dev/null || sudo kubectl create namespace $(NAMESPACE); \
		sudo kubectl create secret docker-registry ecr-secret --namespace $(NAMESPACE) \
		--docker-server=$(AWS_ACCOUNT).dkr.ecr.$(AWS_REGION).amazonaws.com \
		--docker-username=AWS --docker-password='$(ECR_TOKEN)' \
		--dry-run=client -o yaml | sudo kubectl apply -f -"
	@echo "=== yaml 복사 & 배포 ==="
	scp -i $(EC2_KEY) -o StrictHostKeyChecking=no k8s/*.yaml $(EC2_USER)@$(EC2_IP):~/
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) "\
		sudo kubectl apply -f 00-namespace.yaml && \
		sudo kubectl apply -f 01-mysql.yaml && \
		sudo kubectl apply -f 02-redis.yaml && \
		sudo kubectl apply -f 03-app.yaml && \
		sudo kubectl apply -f 04-hpa.yaml"
	@echo "=== metrics-server 설치 ==="
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) "\
		sudo kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml && \
		sudo kubectl patch deployment metrics-server -n kube-system \
		--type='json' -p='[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--kubelet-insecure-tls\"}]'"
	@echo "=== 배포 완료 === http://$(EC2_IP):8081"

ec2-redeploy:
	@echo "=== 이미지 재빌드 & Push ==="
	aws ecr get-login-password --region $(AWS_REGION) | docker login --username AWS --password-stdin $(AWS_ACCOUNT).dkr.ecr.$(AWS_REGION).amazonaws.com
	docker buildx build --platform linux/amd64 -t $(ECR_IMAGE) --push ./backend
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) \
		"sudo kubectl rollout restart deployment/fcfs-app -n $(NAMESPACE)"

ec2-status:
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) \
		"sudo kubectl get hpa,pods -n $(NAMESPACE)"

ec2-logs:
	ssh -i $(EC2_KEY) -o StrictHostKeyChecking=no $(EC2_USER)@$(EC2_IP) \
		"sudo kubectl logs -n $(NAMESPACE) -l app=fcfs-app -f"

ec2-down:
	$(eval INSTANCE_ID=$(shell aws ec2 describe-instances \
		--filters "Name=tag:Name,Values=fcfs-server" "Name=instance-state-name,Values=running,stopped" \
		--query 'Reservations[0].Instances[0].InstanceId' --output text))
	aws ec2 terminate-instances --instance-ids $(INSTANCE_ID)
	@echo "EC2 종료: $(INSTANCE_ID)"

# ── JVM 실험 ──────────────────────────────────────────
experiment:
	./k8s/experiments/run.sh $(filter-out $@,$(MAKECMDGOALS))

%:
	@:
