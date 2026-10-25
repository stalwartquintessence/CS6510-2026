#!/usr/bin/env bash
# Stops what run-local.sh started: by default everything, the gateway first, then the services in
# reverse dependency order. With arguments, only those services (e.g. ./stop-local.sh inventory-service).
cd "$(dirname "$0")"
for name in ${*:-gateway analytics-service transaction-service inventory-service catalog-service}; do
  if [ -f "logs/$name.pid" ]; then
    pid=$(cat "logs/$name.pid")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid"
      while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
      echo "stopped $name"
    fi
    rm -f "logs/$name.pid"
  fi
done
