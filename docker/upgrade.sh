#!/usr/bin/env bash
# Upgrades a controller from Delivery Pipeline 1.4.2 to the 2.0 in this checkout on one Jenkins home:
# 1.4.2 (from the update center) writes its configuration, then the 2.0 image starts on the same home and
# docker/upgrade_check.py checks what loaded. Needs target/delivery-pipeline-plugin.hpi (docker/run.sh build).
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f docker/docker-compose.yml -f docker/docker-compose.upgrade.yml)
export JENKINS_URL="${JENKINS_URL:-http://localhost:${JENKINS_PORT:-8080}}"
mkdir -p docker/out

cleanup() { "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
cleanup
"${COMPOSE[@]}" build jenkins-legacy jenkins
echo "== phase 1: Delivery Pipeline 1.4.2"
"${COMPOSE[@]}" up -d --wait jenkins-legacy
python3 docker/upgrade_check.py legacy
"${COMPOSE[@]}" stop jenkins-legacy
"${COMPOSE[@]}" rm -f jenkins-legacy >/dev/null
echo "== phase 2: Delivery Pipeline 2.0 on the same home"
"${COMPOSE[@]}" up -d --wait jenkins
status=0
python3 docker/upgrade_check.py new || status=$?
if [ "${KEEP:-}" != "1" ]; then cleanup; fi
exit $status
