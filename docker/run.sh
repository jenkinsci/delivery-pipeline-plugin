#!/usr/bin/env bash
# Builds the plugin and the test controller, waits for it, validates it, captures screenshots. Only Docker is
# needed: Maven and the JDK run in a container, with the Maven repository cached in docker/.m2.
#   docker/run.sh all          everything below in order
#   docker/run.sh build        compile the plugin into target/delivery-pipeline-plugin.hpi and build the image
#   docker/run.sh test         run the plugin's own test suite and SpotBugs (mvn verify) in the container
#   docker/run.sh mvn ...      any other Maven command in the container, e.g. docker/run.sh mvn -Dtest=StatusTest test
#   docker/run.sh up           start the controller (http://localhost:8080, admin/admin)
#   docker/run.sh validate     run docker/validate.py against it
#   docker/run.sh screenshots  capture docker/out/*.png with Playwright
#   docker/run.sh down         stop and remove the controller
# LOCAL_MAVEN=1 uses a Maven on the PATH instead of the container.
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f docker/docker-compose.yml)
export JENKINS_URL="${JENKINS_URL:-http://localhost:${JENKINS_PORT:-8080}}"

maven() {
  if [ "${LOCAL_MAVEN:-}" = "1" ]; then
    mvn -B -ntp "$@"
  else
    mkdir -p docker/.m2
    DPP_UID="$(id -u)" DPP_GID="$(id -g)" "${COMPOSE[@]}" run --rm build mvn -B -ntp "$@"
  fi
}
build() {
  maven -DskipTests package
  "${COMPOSE[@]}" build jenkins
}
test_plugin() {
  maven verify
}
up() {
  "${COMPOSE[@]}" up -d --wait jenkins
}
validate() {
  python3 docker/validate.py
}
screenshots() {
  mkdir -p docker/out
  "${COMPOSE[@]}" run --rm screenshots
}
down() {
  "${COMPOSE[@]}" down -v --remove-orphans
}
case "${1:-all}" in
  build) build ;;
  test) test_plugin ;;
  mvn) shift; maven "$@" ;;
  up) up ;;
  validate) validate ;;
  screenshots) screenshots ;;
  down) down ;;
  logs) "${COMPOSE[@]}" logs -f jenkins ;;
  all) build; up; validate; screenshots ;;
  *) echo "usage: $0 {all|build|test|mvn ...|up|validate|screenshots|down|logs}" >&2; exit 2 ;;
esac
