#!/usr/bin/env bash
# Starts the four domain services and the gateway as five separate JVMs, each from its own jar.
# Assumes PostgreSQL is up (docker compose up -d) and the jars are built (./mvnw -DskipTests package).
# Logs go to logs/<service>.log; PIDs to logs/<service>.pid. Stop with ./stop-local.sh.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p logs

wait_for_port() { # name port
  for _ in $(seq 1 120); do
    if nc -z localhost "$2" 2>/dev/null; then return 0; fi
    sleep 0.5
  done
  echo "$1 did not open port $2; see logs/$1.log" >&2
  exit 1
}

start() { # name port
  local jar="$1/target/$1-1.0.0-exec.jar"
  [ -f "$jar" ] || { echo "missing $jar — run ./mvnw -DskipTests package" >&2; exit 1; }
  if [ -f "logs/$1.pid" ] && kill -0 "$(cat "logs/$1.pid")" 2>/dev/null; then
    echo "$1 already running (pid $(cat "logs/$1.pid"))"; return
  fi
  java ${JAVA_OPTS:-} -jar "$jar" > "logs/$1.log" 2>&1 &
  echo $! > "logs/$1.pid"
  wait_for_port "$1" "$2"
  echo "$1 up on :$2 (pid $(cat "logs/$1.pid"))"
}

# Catalog first: it seeds the 2000 items on an empty database.
start catalog-service     9101
start inventory-service   9103
start transaction-service 9102
start analytics-service   9104
start gateway             8080

echo "Waiting for the catalog seed..."
for _ in $(seq 1 120); do
  if grep -q "Seeded catalog\|Catalog already seeded" logs/catalog-service.log; then break; fi
  sleep 0.5
done
grep "Seeded catalog\|Catalog already seeded" logs/catalog-service.log || echo "(no seed message yet — check logs/catalog-service.log)"
echo "Ready: http://localhost:8080"
