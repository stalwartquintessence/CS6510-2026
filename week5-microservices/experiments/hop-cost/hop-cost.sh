#!/usr/bin/env bash
# What one gRPC call costs on this machine (ARCHITECTURE.md, "2 ms per call is high?").
#
# HopProbe times one call at a time, from its own JVM, to a dummy server that answers at once and to the
# real catalog-service. The script runs it with the machine idle, under the default load and under stress,
# sampling the CPU with top. It then does one more default-load run that samples each database's CPU and
# counts the rows transaction-db reads by sequential scan. Fresh databases and JVMs before every load.
#
# Needs the built jars (./mvnw -DskipTests package), Docker, and free ports 8080, 9101-9104 and 9199.
# Takes about 12 minutes; leave the machine otherwise idle while it runs.
# Env: OUT (reports/experiments/hop-cost), NORMAL_SECS (90), STRESS_SECS (120), WARMUP_SECS (30; 0 skips it),
# PROBE_SECS (unset: 10-25 s per probe, as in the committed run).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
W5=$(cd "$here/../.." && pwd)
LC=$(cd "$W5/../load-client" && pwd)
OUT=${OUT:-$W5/reports/experiments/hop-cost}
WARMUP_SECS=${WARMUP_SECS:-30}
NORMAL_SECS=${NORMAL_SECS:-90}
STRESS_SECS=${STRESS_SECS:-120}
work=$here/target
CP="$work/classes:$work/lib/*"
mkdir -p "$OUT" "$work/lib" "$work/classes" "$work/reports"
stamp=$(date +%Y%m%d-%H%M%S)
log=$OUT/hop-cost-$stamp.txt

note() { echo "$(date +%T) $*" | tee -a "$log"; }
cpu() { note "$1 $(top -l 2 -n 0 -s 5 | grep 'CPU usage' | tail -1)"; }
probe() { java -cp "$CP" HopProbe client "$@" | tee -a "$log"; }
fresh() {
  cd "$W5"; ./stop-local.sh >/dev/null 2>&1; docker compose down -v >/dev/null 2>&1
  docker compose up -d --wait >/dev/null 2>&1; ./run-local.sh >/dev/null || { note "run-local failed"; exit 1; }; sleep 3
}
load() { # stations seconds reportDir -> runs the load client in the background, output in $work/load.out
  (cd "$LC" && ./run.sh --baseUrl=http://localhost:8080 --stations="$1" --duration="$2" --reportDir="$3") \
    > "$work/load.out" 2>&1 &
  loader=$!
}
finish_load() {
  wait "$loader"
  grep -E 'Transactions:|_TRANSACTION|SCAN_ITEM' "$work/load.out" | tee -a "$log"
  for _ in $(seq 1 12); do   # in-flight sagas would count as invariant failures
    n=$(docker exec supermarket-week5-transaction-db psql -U transaction -d transaction -Atc \
        "SELECT count(*) FROM transactions WHERE status IN ('COMPLETING', 'COMPENSATING')")
    [ "$n" = 0 ] && break; sleep 5
  done
  (cd "$W5" && ./verify-invariant.sh | tail -1 | tee -a "$log")
}

for p in 8080 9101 9102 9103 9104 9199; do
  lsof -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1 && { echo "port $p is busy" >&2; exit 1; }
done
jar=$W5/gateway/target/gateway-1.0.0-exec.jar
[ -f "$jar" ] || { echo "build first: ./mvnw -DskipTests package" >&2; exit 1; }
# The probe needs only the generated stubs and grpc-java, which the gateway's jar already bundles.
(cd "$work/lib" && unzip -ojq "$jar" 'BOOT-INF/lib/contracts-*' 'BOOT-INF/lib/grpc-*' 'BOOT-INF/lib/protobuf-*' \
  'BOOT-INF/lib/guava-*' 'BOOT-INF/lib/failureaccess-*' 'BOOT-INF/lib/perfmark-*' 'BOOT-INF/lib/annotations-*')
javac -d "$work/classes" -cp "$CP" "$here/HopProbe.java" || exit 1

note "machine: $(sysctl -n hw.model) $(sysctl -n hw.ncpu) cores; $(pmset -g batt | head -1)"
note "probe: one call at a time from its own JVM; 'paced' = 1 ms pause between calls, 'back-to-back' = none"
java -cp "$CP" HopProbe server 9199 >/dev/null 2>&1 &
dummy=$!
trap 'kill $dummy 2>/dev/null; cd "$W5"; ./stop-local.sh >/dev/null 2>&1; docker compose down -v >/dev/null 2>&1' EXIT
for _ in $(seq 1 50); do lsof -iTCP:9199 -sTCP:LISTEN >/dev/null 2>&1 && break; sleep 0.2; done

if [ "$WARMUP_SECS" -gt 0 ]; then
  note "### warm-up: 10 stations x ${WARMUP_SECS} s, discarded"
  fresh; load 10 "$WARMUP_SECS" "$work/reports"; wait "$loader"
fi

note "### IDLE (all services up, no load)"
fresh
cpu "cpu idle:"
probe "idle   dummy   back-to-back" localhost:9199 SKU-000500 20000 "${PROBE_SECS:-10}" 0
probe "idle   dummy   paced"        localhost:9199 SKU-000500 20000 "${PROBE_SECS:-15}" 1000
probe "idle   catalog back-to-back" localhost:9101 SKU-000500 20000 "${PROBE_SECS:-10}" 0
probe "idle   catalog paced"        localhost:9101 SKU-000500 20000 "${PROBE_SECS:-15}" 1000

note "### NORMAL load (10 stations x ${NORMAL_SECS} s)"
fresh; load 10 "$NORMAL_SECS" "$OUT"
sleep $((NORMAL_SECS / 8))
cpu "cpu normal:"
probe "normal dummy   paced"        localhost:9199 SKU-000500 10000 "${PROBE_SECS:-20}" 1000
probe "normal catalog paced"        localhost:9101 SKU-000500 10000 "${PROBE_SECS:-20}" 1000
finish_load

note "### STRESS load (100 stations x ${STRESS_SECS} s)"
fresh; load 100 "$STRESS_SECS" "$OUT"
sleep $((STRESS_SECS / 6))
cpu "cpu stress:"
top -l 2 -s 5 -o cpu -stats command,cpu -n 10 | awk '/^COMMAND/{blk++} blk==2' | tee -a "$log"
probe "stress dummy   paced"        localhost:9199 SKU-000500 10000 "${PROBE_SECS:-25}" 1000
probe "stress catalog paced"        localhost:9101 SKU-000500 10000 "${PROBE_SECS:-25}" 1000
finish_load

note "### where the CPU goes: 10 stations x 40 s, no probe; docker stats at +20 s and +30 s"
fresh; load 10 40 "$work/reports"
sleep 20; docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}' | tee -a "$log"
sleep 10; docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}' | tee -a "$log"
finish_load
docker exec supermarket-week5-transaction-db psql -U transaction -d transaction -c \
  "SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup FROM pg_stat_user_tables ORDER BY seq_tup_read DESC" \
  | tee -a "$log"
docker exec supermarket-week5-transaction-db psql -U transaction -d transaction -c \
  "EXPLAIN SELECT * FROM transaction_items WHERE tx_id = 'x'" | tee -a "$log"
note "=== done; written to $log"
