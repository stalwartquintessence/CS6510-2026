#!/usr/bin/env bash
# The correctness invariant, checked across the two databases a sale now spans.
#
# Weeks 1-4 checked it with one SQL join, because baskets and stock were in one database. Now
# the units sold are in transaction-db and the stock is in inventory-db, so each side is queried
# on its own and the results are joined here. Run it after a load run has finished (with sagas
# still in flight the cross-database checks are expected to disagree until they settle).
#
# Exits non-zero if any check fails.
set -euo pipefail
INITIAL=${INITIAL_STOCK:-10000}
TX="docker exec -i supermarket-week5-transaction-db psql -U transaction -d transaction -AtX -F,"
INV="docker exec -i supermarket-week5-inventory-db psql -U inventory -d inventory -AtX -F,"
export LC_ALL=C
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# transaction-db: units sold per SKU in COMPLETED transactions, the sale id of each COMPLETED
# transaction (its id and the attempt that completed it), and sagas still in flight.
$TX -c "SELECT ti.sku, SUM(ti.quantity) FROM transaction_items ti JOIN transactions t ON t.id = ti.tx_id
        WHERE t.status = 'COMPLETED' GROUP BY ti.sku" | sort > "$tmp/sold"
$TX -c "SELECT id || '/' || saga_attempt FROM transactions WHERE status = 'COMPLETED'" | sort > "$tmp/completed"
in_flight=$($TX -c "SELECT count(*) FROM transactions WHERE status IN ('COMPLETING', 'COMPENSATING')")

# inventory-db: stock per SKU, units taken and requested per SKU by applied sales, applied sale ids.
$INV -c "SELECT s.sku, s.stock, COALESCE(a.taken, 0), COALESCE(a.requested, 0) FROM stock_items s
         LEFT JOIN (SELECT l.sku, SUM(l.applied) AS taken, SUM(l.requested) AS requested
                    FROM sale_lines l JOIN sales x ON x.sale_id = l.sale_id
                    WHERE x.status = 'APPLIED' GROUP BY l.sku) a ON a.sku = s.sku" | sort > "$tmp/stock"
$INV -c "SELECT sale_id FROM sales WHERE status = 'APPLIED'" | sort > "$tmp/applied"

# sku, stock, taken, requested, sold
join -t, -a 2 -e 0 -o '2.1,2.2,2.3,2.4,1.2' "$tmp/sold" "$tmp/stock" > "$tmp/joined"

read -r sold decremented floored lost ledger mismatched negative < <(awk -F, -v initial="$INITIAL" '
  { sold += $5; dec += initial - $2; floored += $4 - $3 }
  $2 > 0 && initial - $2 != $5 { lost++ }        # the weeks 1-4 check: unfloored SKUs only
  initial - $2 != $3          { ledger++ }       # exact: stock moved by what the ledger says was taken
  $4 != $5                    { mismatched++ }   # inventory was asked for exactly what was sold
  $2 < 0                      { negative++ }
  END { printf "%d %d %d %d %d %d %d\n", sold, dec, floored, lost, ledger, mismatched, negative }
' "$tmp/joined")
completed_only=$(comm -23 "$tmp/completed" "$tmp/applied" | wc -l | tr -d ' ')
applied_only=$(comm -13 "$tmp/completed" "$tmp/applied" | wc -l | tr -d ' ')

printf '%-44s %s\n' \
  "units sold (transaction-db, COMPLETED)" "$sold" \
  "units decremented (inventory-db)" "$decremented" \
  "demand beyond supply (floored at 0)" "$floored" \
  "" "" \
  "lost_update_bugs" "$lost" \
  "ledger_mismatches" "$ledger" \
  "sold_vs_requested_mismatches" "$mismatched" \
  "negative_stock_rows" "$negative" \
  "completed_tx_without_applied_sale" "$completed_only" \
  "applied_sale_without_completed_tx" "$applied_only" \
  "sagas_in_flight" "$in_flight"

failures=$((lost + ledger + mismatched + negative + completed_only + applied_only + in_flight))
if [ "$failures" -ne 0 ]; then
  echo "INVARIANT VIOLATED" >&2
  exit 1
fi
echo "invariant holds"
