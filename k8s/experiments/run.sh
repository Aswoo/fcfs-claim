#!/usr/bin/env bash
# JVM 실험 실행 스크립트
# 사용법: ./k8s/experiments/run.sh <command>
set -euo pipefail

NAMESPACE="fcfs"
APP_LABEL="app=fcfs-app"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGE_TAG_FILE="$ROOT_DIR/.image-tag"
IMAGE_BASE="fcfs-claim-app"

# ──────────────────────────────────────────
# 내부 헬퍼
# ──────────────────────────────────────────

pod_name() {
  kubectl get pod -n "$NAMESPACE" -l "$APP_LABEL" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null
}

wait_ready() {
  echo "⏳ 파드 준비 대기..."
  kubectl rollout status deployment/fcfs-app -n "$NAMESPACE" --timeout=90s
  echo "✅ $(pod_name)"
}

jvm_info() {
  echo ""
  echo "──── JVM 정보 ────────────────────────────"
  kubectl logs -n "$NAMESPACE" "$(pod_name)" 2>/dev/null \
    | grep -A 4 "JVM 정보" \
    || echo "(JVM 정보 로그 없음 — ./run.sh build 후 rollout restart 필요)"
  echo "───────────────────────────────────────────"
  echo ""
}

current_tag() {
  cat "$IMAGE_TAG_FILE" 2>/dev/null || echo "latest"
}

apply_and_wait() {
  local tag
  tag=$(current_tag)
  echo "📦 적용: $1  (이미지 태그: $tag)"
  kubectl apply -f "$SCRIPT_DIR/$1"
  kubectl set image deployment/fcfs-app \
    fcfs-app="$IMAGE_BASE:$tag" -n "$NAMESPACE" >/dev/null
  sleep 3
  wait_ready
  jvm_info
}

influxdb_out() {
  if curl -sf --max-time 1 http://localhost:8086/ping >/dev/null 2>&1; then
    echo "--out influxdb=http://localhost:8086/k6"
  else
    echo ""
  fi
}

run_k6() {
  local script="$1"
  local duration="${2:-}"
  local out_flag
  out_flag=$(influxdb_out)

  if [ -n "$out_flag" ]; then
    echo "📊 Grafana 연동 활성 → http://localhost:3000"
    open "http://localhost:3000" 2>/dev/null || true
  fi
  echo "🚀 k6 시작: $script"
  echo ""
  if [ -n "$duration" ]; then
    # shellcheck disable=SC2086
    k6 run --duration "$duration" $out_flag "$ROOT_DIR/k6/$script"
  else
    # shellcheck disable=SC2086
    k6 run $out_flag "$ROOT_DIR/k6/$script"
  fi
}

countdown() {
  local sec="$1" msg="$2"
  echo "$msg"
  for i in $(seq "$sec" -1 1); do
    printf "\r  %ds 후 시작..." "$i"
    sleep 1
  done
  echo ""
}

show_result_oom() {
  local pod
  pod=$(pod_name)
  echo ""
  echo "══════════════════ 결과 ══════════════════"

  local reason exit_code restarts
  reason=$(kubectl get pod -n "$NAMESPACE" "$pod" \
    -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}' 2>/dev/null || echo "")
  exit_code=$(kubectl get pod -n "$NAMESPACE" "$pod" \
    -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null || echo "")
  restarts=$(kubectl get pod -n "$NAMESPACE" "$pod" \
    -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo "0")

  echo ""
  if [ "$reason" = "OOMKilled" ]; then
    echo "  ❌ OOMKilled 발생"
    echo "     Reason:    $reason"
    echo "     Exit Code: $exit_code  (128 + SIGKILL)"
    echo "     RESTARTS:  $restarts"
  elif [ "$restarts" = "0" ]; then
    echo "  ✅ OOMKilled 없이 버텼습니다 (RESTARTS: 0)"
  else
    echo "  ⚠️  재시작 $restarts 회 발생 — describe로 원인 확인 필요"
  fi

  echo ""
  echo "  [메모리 현황]"
  kubectl top pod -n "$NAMESPACE" 2>/dev/null || echo "  (metrics-server 없음)"
  echo "══════════════════════════════════════════"
}

# ──────────────────────────────────────────
# 커맨드
# ──────────────────────────────────────────

cmd_build() {
  local tag
  tag="dev-$(date +%Y%m%d%H%M%S)"
  echo "=== 이미지 빌드 (태그: $tag) ==="
  docker build -t "$IMAGE_BASE:$tag" "$ROOT_DIR/backend"
  echo "$tag" > "$IMAGE_TAG_FILE"
  echo ""
  echo "✅ 빌드 완료. 태그: $tag"
  echo "   실험 실행 시 자동으로 이 태그가 적용됩니다."
}

cmd_status() {
  echo "=== 현재 상태 ==="
  kubectl get pod -n "$NAMESPACE"
  echo ""
  kubectl top pod -n "$NAMESPACE" 2>/dev/null || true
  jvm_info
}

# ──── 실험 1: OOMKilled ────────────────────

cmd_oom_bad() {
  echo "=== 실험 1-1: OOMKilled 재현 ==="
  echo "설정: memory limit=256Mi, MaxRAMPercentage=90%"
  echo ""
  echo "┌─ 터미널 세팅 ───────────────────────────────┐"
  echo "│  지금 이 터미널 (A) : 실험 실행 + k6 자동 시작  │"
  echo "│  터미널 B (새로 열기): ./run.sh oom-watch      │"
  echo "└─────────────────────────────────────────────┘"
  echo ""
  apply_and_wait "01-oom-bad.yaml"
  countdown 10 "터미널 B에서 ./run.sh oom-watch 실행 후 대기 (10초)..."
  run_k6 "01_queue_stress.js"
  show_result_oom
}

cmd_oom_good() {
  echo "=== 실험 1-2: OOMKilled 해결 ==="
  echo "설정: memory limit=512Mi, MaxRAMPercentage=60%"
  echo ""
  echo "┌─ 터미널 세팅 ───────────────────────────────┐"
  echo "│  지금 이 터미널 (A) : 실험 실행 + k6 자동 시작  │"
  echo "│  터미널 B (유지)    : ./run.sh oom-watch       │"
  echo "└─────────────────────────────────────────────┘"
  echo ""
  apply_and_wait "01-oom-good.yaml"
  countdown 5 "5초 후 k6 시작..."
  run_k6 "01_queue_stress.js"
  show_result_oom
}

cmd_oom_watch() {
  echo "=== 파드 상태 감시 중 (Ctrl+C 로 종료) ==="
  echo ""
  while true; do
    local pod
    pod=$(pod_name 2>/dev/null || echo "")

    if [ -z "$pod" ]; then
      echo "[$(date '+%H:%M:%S')]  파드 없음 (재시작 중...)"
      sleep 2
      continue
    fi

    local status restarts
    status=$(kubectl get pod -n "$NAMESPACE" "$pod" \
      -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")
    restarts=$(kubectl get pod -n "$NAMESPACE" "$pod" \
      -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo "?")

    local memory=""
    memory=$(kubectl top pod -n "$NAMESPACE" "$pod" --no-headers 2>/dev/null \
      | awk '{print $3}' || echo "?")

    printf "[%s]  %-42s STATUS: %-10s  RESTARTS: %s  MEM: %s\n" \
      "$(date '+%H:%M:%S')" "$pod" "$status" "$restarts" "$memory"

    local last_reason last_exit
    last_reason=$(kubectl get pod -n "$NAMESPACE" "$pod" \
      -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}' 2>/dev/null || echo "")
    last_exit=$(kubectl get pod -n "$NAMESPACE" "$pod" \
      -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null || echo "")

    if [ -n "$last_reason" ]; then
      echo ""
      echo "  ┌─ Last State ──────────────────────────────"
      echo "  │  Reason:    $last_reason"
      echo "  │  Exit Code: $last_exit"
      [ "$last_reason" = "OOMKilled" ] && \
        echo "  │  ❌ OOMKilled — OS가 메모리 초과로 강제 종료"
      echo "  └───────────────────────────────────────────"
      echo ""
    fi

    sleep 3
  done
}

# ──── 실험 2: CPU ──────────────────────────

cmd_cpu_bad() {
  echo "=== 실험 2-1: CPU 잘못 인식 (UseContainerSupport OFF) ==="
  echo ""
  echo "┌─ 터미널 세팅 ──────────────────────────────┐"
  echo "│  터미널 1개로 충분                            │"
  echo "│  cpu-bad → cpu-good 순서로 실행해 로그를 비교  │"
  echo "└────────────────────────────────────────────┘"
  echo ""
  apply_and_wait "02-cpu-bad.yaml"

  echo "──── CPU 인식 결과 ────"
  kubectl logs -n "$NAMESPACE" "$(pod_name)" \
    | grep "인식된 CPU" || echo "(JVM 정보 로그 없음 — build 필요)"

  echo ""
  echo "다음 단계: ./run.sh cpu-good"
}

cmd_cpu_good() {
  echo "=== 실험 2-2: CPU 올바른 설정 (UseContainerSupport ON + ActiveProcessorCount=2) ==="
  echo ""
  apply_and_wait "02-cpu-good.yaml"

  echo "──── CPU 인식 결과 비교 ────"
  echo "  bad  설정: $(kubectl logs -n "$NAMESPACE" "$(pod_name)" 2>/dev/null | grep "인식된 CPU" || echo "로그 없음")"
  kubectl logs -n "$NAMESPACE" "$(pod_name)" \
    | grep "인식된 CPU" || echo "(JVM 정보 로그 없음 — build 필요)"
}

# ──── 실험 3: GC ───────────────────────────

cmd_gc_log() {
  echo "=== 실험 3-1: GC 로그 활성화 ==="
  echo ""
  echo "┌─ 터미널 세팅 ───────────────────────────────┐"
  echo "│  지금 이 터미널 (A) : 실험 실행 + k6 자동 시작  │"
  echo "│  터미널 B (새로 열기): ./run.sh gc-tail        │"
  echo "└─────────────────────────────────────────────┘"
  echo ""
  apply_and_wait "03-gc-logging.yaml"
  countdown 15 "터미널 B에서 ./run.sh gc-tail 실행 후 대기 (15초)..."
  run_k6 "01_queue_stress.js" "60s"
  cmd_gc_stats
}

cmd_gc_stress() {
  echo "=== 실험 3-2: GC 압박 (힙 64MB 강제 축소) ==="
  echo ""
  echo "┌─ 터미널 세팅 ───────────────────────────────┐"
  echo "│  지금 이 터미널 (A) : 실험 실행 + k6 자동 시작  │"
  echo "│  터미널 B (새로 열기): ./run.sh gc-tail        │"
  echo "│  Full GC가 터지면 k6 p(95) 응답 시간 급등함     │"
  echo "└─────────────────────────────────────────────┘"
  echo ""
  apply_and_wait "03-gc-stress.yaml"
  countdown 15 "터미널 B에서 ./run.sh gc-tail 실행 후 대기 (15초)..."
  run_k6 "01_queue_stress.js" "30s"
  cmd_gc_stats
}

cmd_gc_tail() {
  local pod
  pod=$(pod_name)
  echo "=== GC 로그 스트리밍 (Ctrl+C로 종료) ==="
  echo "파드: $pod"
  echo ""
  echo "gc.log 생성 대기 중..."
  local i=0
  until kubectl exec -n "$NAMESPACE" "$pod" -- test -f /tmp/gc.log 2>/dev/null; do
    sleep 1
    i=$((i+1))
    [ "$i" -gt 60 ] && echo "gc.log 없음 — gc-log 또는 gc-stress 먼저 실행하세요" && exit 1
  done
  echo ""
  echo "읽는 법:"
  echo "  Pause Young ... 4ms    ← Young GC, 빠름, 정상"
  echo "  Pause Full  ... 200ms  ← Full GC! 앱이 이 시간만큼 멈춤"
  echo "──────────────────────────────────────────"
  kubectl exec -n "$NAMESPACE" "$pod" -- tail -f /tmp/gc.log
}

cmd_gc_stats() {
  local pod
  pod=$(pod_name)
  echo ""
  echo "📥 gc.log 복사 중..."
  kubectl cp "$NAMESPACE/$pod:/tmp/gc.log" /tmp/gc-experiment.log 2>/dev/null || {
    echo "gc.log 없음 — gc-log 또는 gc-stress를 먼저 실행하세요"
    return
  }

  echo ""
  echo "══════════════════ GC 통계 ═══════════════"
  echo ""
  echo "  [Young GC]"
  local yc
  yc=$(grep -c "Pause Young" /tmp/gc-experiment.log 2>/dev/null || echo 0)
  echo "  횟수: $yc 회"
  [ "$yc" -gt 0 ] && grep "Pause Young" /tmp/gc-experiment.log \
    | grep -oE '[0-9]+\.[0-9]+ms' \
    | awk -F'ms' '{s+=$1;c++} END{printf "  평균: %.2fms\n",s/c}'

  echo ""
  echo "  [Full GC]"
  local fc
  fc=$(grep -c "Pause Full" /tmp/gc-experiment.log 2>/dev/null || echo 0)
  echo "  횟수: $fc 회"
  if [ "$fc" -gt 0 ]; then
    grep "Pause Full" /tmp/gc-experiment.log \
      | grep -oE '[0-9]+\.[0-9]+ms' \
      | awk -F'ms' '{s+=$1;c++} END{printf "  평균: %.2fms\n",s/c}'
    echo ""
    echo "  ⚠️  Full GC 상위 5개 (긴 것부터):"
    grep "Pause Full" /tmp/gc-experiment.log \
      | grep -oE '[0-9]+\.[0-9]+ms' \
      | sort -t'.' -k1 -rn | head -5 \
      | awk '{printf "     %s\n", $0}'
  else
    echo "  ✅ Full GC 없음"
  fi

  echo ""
  echo "  [GC 직전 힙 최대 사용량]"
  grep "Pause" /tmp/gc-experiment.log \
    | grep -oE '[0-9]+M->' | grep -oE '[0-9]+' \
    | sort -n | tail -1 \
    | awk '{printf "  %sMB\n", $1}' 2>/dev/null || echo "  측정 불가"
  echo "══════════════════════════════════════════"
}

# ──── 공통 유틸 ────────────────────────────

cmd_logs() {
  echo "=== 앱 로그 스트리밍 (Ctrl+C로 종료) ==="
  kubectl logs -n "$NAMESPACE" -l "$APP_LABEL" -f --max-log-requests=5
}

cmd_restore() {
  echo "=== 원래 설정으로 복구 ==="
  kubectl apply -f "$SCRIPT_DIR/../03-app.yaml"
  wait_ready
  jvm_info
  echo "✅ 복구 완료"
}

# ──────────────────────────────────────────
# 도움말
# ──────────────────────────────────────────

usage() {
  cat <<'EOF'
JVM 실험 실행 스크립트
사용법: ./k8s/experiments/run.sh <command>

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 최초 1회 — 코드 수정 후 이미지 교체
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  build        Docker 이미지 재빌드
               (FcfsClaimApplication.java 수정 시 필요)
  status       현재 파드 상태 + JVM 정보 확인

  이미지 교체 후:
    kubectl rollout restart deployment/fcfs-app -n fcfs

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 실험 1 — OOMKilled     [터미널 2개]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  터미널 A:  ./run.sh oom-bad    k6 자동 실행 + 결과 출력
  터미널 B:  ./run.sh oom-watch  파드 상태 3초마다 polling

  oom-bad      설정 적용 + k6 실행 → OOMKilled 재현
  oom-good     설정 적용 + k6 실행 → 정상 버팀 확인
  oom-watch    파드 상태 실시간 감시 (OOMKilled 감지)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 실험 2 — CPU 인식       [터미널 1개]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  cpu-bad      UseContainerSupport OFF → 노드 전체 CPU 인식
  cpu-good     UseContainerSupport ON  → 컨테이너 limit 인식

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 실험 3 — GC 로그        [터미널 2개]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  터미널 A:  ./run.sh gc-stress  k6 자동 실행 + 통계 출력
  터미널 B:  ./run.sh gc-tail    GC 로그 실시간 스트리밍

  gc-log       GC 로그 활성화 + k6 60초 부하
  gc-stress    힙 64MB 강제 축소 + k6 30초 부하 → Full GC 유발
  gc-tail      GC 로그 실시간 스트리밍 (터미널 B 전용)
  gc-stats     GC 통계 분석 (Young/Full GC 횟수, 평균 시간)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 Grafana 모니터링 (선택)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  docker compose up -d influxdb grafana
  → http://localhost:3000  (admin / admin)
  → k6 결과 자동으로 InfluxDB → Grafana 대시보드에 표시됨
  InfluxDB가 실행 중이면 k6 --out influxdb 자동 활성화

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 공통 유틸
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  logs         앱 로그 실시간 스트리밍
  restore      원래 설정으로 복구 (k8s/03-app.yaml)
EOF
}

case "${1:-}" in
  build)      cmd_build ;;
  status)     cmd_status ;;
  oom-bad)    cmd_oom_bad ;;
  oom-good)   cmd_oom_good ;;
  oom-watch)  cmd_oom_watch ;;
  cpu-bad)    cmd_cpu_bad ;;
  cpu-good)   cmd_cpu_good ;;
  gc-log)     cmd_gc_log ;;
  gc-stress)  cmd_gc_stress ;;
  gc-tail)    cmd_gc_tail ;;
  gc-stats)   cmd_gc_stats ;;
  logs)       cmd_logs ;;
  restore)    cmd_restore ;;
  *)          usage ;;
esac
