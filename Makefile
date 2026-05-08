NAMESPACE := fcfs
IMAGE     := fcfs-claim-app:latest

# ── 개발 ────────────────────────────────────────────
dev:
	./dev.sh

# ── K8s 배포 ─────────────────────────────────────────
deploy:
	./k8s/deploy.sh

redeploy:
	docker build -t $(IMAGE) ./backend
	kubectl rollout restart deployment/fcfs-app -n $(NAMESPACE)
	kubectl rollout status deployment/fcfs-app -n $(NAMESPACE) --timeout=90s

# ── 포트포워드 ────────────────────────────────────────
port:
	kubectl port-forward --address 0.0.0.0 -n $(NAMESPACE) svc/fcfs-app 8081:8081

# ── 상태 확인 ─────────────────────────────────────────
status:
	kubectl get pods -n $(NAMESPACE)

logs:
	kubectl logs -n $(NAMESPACE) -l app=fcfs-app -f

# ── JVM 실험 ──────────────────────────────────────────
experiment:
	./k8s/experiments/run.sh $(filter-out $@,$(MAKECMDGOALS))

%:
	@:
