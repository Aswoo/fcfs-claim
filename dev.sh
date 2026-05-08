#!/usr/bin/env bash
# 로컬 개발 서버 시작 스크립트
# MySQL + Redis: Docker Compose
# Spring Boot: 로컬 직접 실행 (local 프로파일)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

stop() {
  echo ""
  echo "종료 중..."
  docker compose -f "$ROOT_DIR/docker-compose.yml" stop mysql redis
  exit 0
}
trap stop INT TERM

echo "=== MySQL + Redis 시작 ==="
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d mysql redis

echo ""
echo "=== MySQL 준비 대기 ==="
until docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T mysql \
  mysqladmin ping -h localhost --silent 2>/dev/null; do
  printf "."
  sleep 1
done
echo " 준비됨"

echo ""
echo "=== Spring Boot 시작 (local 프로파일) ==="
echo "  API: http://localhost:8081"
echo "  종료: Ctrl+C"
echo ""
cd "$ROOT_DIR/backend" && ./gradlew bootRun
