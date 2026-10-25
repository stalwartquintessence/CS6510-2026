#!/usr/bin/env bash
# Starts the four microservices and the API gateway as five separate JVMs, each from its own jar.
# Assumes the four databases are up (docker compose up -d) and the jars are built
# (./mvnw -DskipTests package). Logs go to logs/<service>.log (the previous run's to
# logs/<service>.prev.log); PIDs to logs/<service>.pid. Stop with ./stop-local.sh.
#
#   ./run-local.sh                       everything, in dependency order
#   ./run-local.sh inventory-service     one service on its own: deploy it without touching the rest
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p logs

ALL="catalog-service inventory-service transaction-service analytics-service gateway"

port_of() {
  case "$1" in
    catalog-service) echo 9101 ;;
    transaction-service) echo 9102 ;;
    inventory-service) echo 9103 ;;
    analytics-service) echo 9104 ;;
    gateway) echo 8080 ;;
    *) echo "unknown service: $1 (one of: $ALL)" >&2; exit 2 ;;
  esac
}

wait_for_port() { # name port pid
  for _ in $(seq 1 120); do
    # The port must open *and* our process must still be alive: a JVM that failed to bind
    # exits, and a port opened by something else must not pass for this service being up.
    kill -0 "$3" 2>/dev/null || { echo "$1 exited during startup; see logs/$1.log" >&2; exit 1; }
    if nc -z localhost "$2" 2>/dev/null; then return 0; fi
    sleep 0.5
  done
  echo "$1 did not open port $2; see logs/$1.log" >&2
  exit 1
}

wait_for_log() { # name pattern
  for _ in $(seq 1 240); do
    if grep -q "$2" "logs/$1.log"; then grep -m1 "$2" "logs/$1.log" | sed 's/^.*INFO [0-9]* --- //'; return 0; fi
    sleep 0.5
  done
  echo "$1 never logged '$2'; see logs/$1.log" >&2
  exit 1
}

start() { # name
  local name="$1" port jar
  port=$(port_of "$name")
  jar="$name/target/$name-1.0.0-exec.jar"
  [ -f "$jar" ] || { echo "missing $jar — run ./mvnw -DskipTests package" >&2; exit 1; }
  if [ -f "logs/$name.pid" ] && kill -0 "$(cat "logs/$name.pid")" 2>/dev/null; then
    echo "$name already running (pid $(cat "logs/$name.pid"))"; return
  fi
  if nc -z localhost "$port" 2>/dev/null; then
    echo "port $port is already in use (another week's server?); not starting $name" >&2; exit 1
  fi
  [ -f "logs/$name.log" ] && mv "logs/$name.log" "logs/$name.prev.log"
  java ${JAVA_OPTS:-} -jar "$jar" > "logs/$name.log" 2>&1 &
  echo $! > "logs/$name.pid"
  wait_for_port "$name" "$port" "$(cat "logs/$name.pid")"
  echo "$name up on :$port (pid $(cat "logs/$name.pid"))"
  # Seeding runs just after the port opens; whatever depends on the data must wait for it.
  case "$name" in
    catalog-service) wait_for_log "$name" "Seeded catalog\|Catalog already seeded" ;;
    inventory-service) wait_for_log "$name" "Seeded stock\|Stock already seeded" ;;
  esac
}

# Catalog first (it seeds the items), then inventory (it seeds stock from the catalog), then the
# services that call them, then the gateway in front.
for name in ${*:-$ALL}; do
  start "$name"
done
[ $# -eq 0 ] && echo "Ready: http://localhost:8080"
exit 0
