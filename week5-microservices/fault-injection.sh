#!/usr/bin/env bash
# Fault injection against a running system (./run-local.sh): drives the unmodified load client
# and, part-way through, kills one service with SIGKILL — no graceful shutdown, so whatever it
# was doing is cut off mid-flight — then restarts it. Afterwards it waits for the transaction
# service's saga recovery to finish any sagas the fault left stuck, and checks the invariant.
#
#   ./fault-injection.sh inventory-service     the saga's remote step disappears
#   ./fault-injection.sh transaction-service   the orchestrator dies mid-saga
#   ./fault-injection.sh analytics-service     redeploy one service; checkout must not notice
#   ./fault-injection.sh catalog-service       the item cache must carry checkout
#
# Env: STATIONS (10), DURATION (60), KILL_AT (20), DOWN_FOR (15) — seconds.
# Writes reports/fault-injection/<service>-<time>.txt (timeline, time to recover, client report,
# invariant) and the client's JSON report beside it.
#
# "Time to recover" is from the restarted service being up (port open, startup finished) to the
# first request through the gateway that needs it succeeding: for inventory and transaction a full
# checkout (start, scan, complete -> 200), which also exercises the saga's call to inventory.
set -euo pipefail
cd "$(dirname "$0")"
victim=${1:?usage: $0 catalog-service|transaction-service|inventory-service|analytics-service}
STATIONS=${STATIONS:-10}
DURATION=${DURATION:-60}
KILL_AT=${KILL_AT:-20}
DOWN_FOR=${DOWN_FOR:-15}

case "$victim" in   # the public endpoint that depends on the victim, probed while it is down
  catalog-service) probe=/items ;;
  transaction-service) probe=/transactions/tx-probe ;;
  inventory-service) probe=/inventory/low-stock ;;
  analytics-service) probe=/analytics/popular-items ;;
  *) echo "unknown service: $victim" >&2; exit 2 ;;
esac
[ -f "logs/$victim.pid" ] && kill -0 "$(cat "logs/$victim.pid")" 2>/dev/null \
  || { echo "$victim is not running; start the system with ./run-local.sh" >&2; exit 1; }

mkdir -p reports/fault-injection
out="reports/fault-injection/$victim-$(date +%Y%m%d-%H%M%S).txt"
note() { echo "$(date +%T) $*" | tee -a "$out"; }
now() { python3 -c 'import time; print(time.time())'; }

checkout() { # one full checkout through the gateway; prints the complete's HTTP status
  local tx
  tx=$(curl -s -XPOST localhost:8080/transactions -H 'Content-Type: application/json' -d '{"stationId":"probe"}' \
       | sed -n 's/.*"transactionId":"\([^"]*\)".*/\1/p')
  [ -n "$tx" ] || { echo 000; return; }
  curl -s -o /dev/null -XPOST "localhost:8080/transactions/$tx/items" -H 'Content-Type: application/json' -d '{"sku":"SKU-000500"}'
  curl -s -o /dev/null -w '%{http_code}' -XPOST "localhost:8080/transactions/$tx/complete"
}

recovered() { # does the path through the gateway that needs the victim work again?
  case "$victim" in
    inventory-service|transaction-service) [ "$(checkout)" = 200 ] ;;
    *) [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080$probe")" = 200 ] ;;
  esac
}

note "load: $STATIONS stations x ${DURATION}s; kill -9 $victim at +${KILL_AT}s, restart after ${DOWN_FOR}s"
(cd ../load-client && ./run.sh --baseUrl=http://localhost:8080 --stations="$STATIONS" --duration="$DURATION" \
  --reportDir=../week5-microservices/reports/fault-injection) > "$out.client" 2>&1 &
client=$!

sleep "$KILL_AT"
kill -9 "$(cat "logs/$victim.pid")"
rm -f "logs/$victim.pid"
note "killed $victim"
sleep 1
note "GET $probe -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080$probe")"
sleep $((DOWN_FOR - 1))
note "restarting $victim"
./run-local.sh "$victim" | sed 's/^/    /' | tee -a "$out"
up=$(now)
for _ in $(seq 1 300); do recovered && break; sleep 0.1; done
note "$(python3 -c "print('time to recover: %.1f s after $victim was up' % ($(now) - $up))")"
note "GET $probe -> HTTP $(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080$probe")"

wait "$client" || true
note "load finished"
sed -n '/LOAD TEST REPORT/,$p' "$out.client" >> "$out"
rm -f "$out.client"

# Recovery runs every 5 s for sagas in flight longer than 30 s.
for _ in $(seq 1 24); do
  n=$(docker exec supermarket-week5-transaction-db psql -U transaction -d transaction -Atc \
      "SELECT count(*) FROM transactions WHERE status IN ('COMPLETING', 'COMPENSATING')")
  note "sagas in flight: $n"
  [ "$n" = 0 ] && break
  sleep 5
done
grep -h 'Recovered' logs/transaction-service.log 2>/dev/null | sed 's/^.*SagaRecovery *: /    recovery: /' | tee -a "$out" || true
./verify-invariant.sh | tee -a "$out"
