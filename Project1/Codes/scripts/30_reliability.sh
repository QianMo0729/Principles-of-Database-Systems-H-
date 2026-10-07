#!/usr/bin/env bash
# Experiment C (reliability): how each DBMS behaves when things go wrong, measured on the VM spec
# of the real server (2 CPU / 2 GiB by default). Every test ends by inspecting the stored data.
# Usage: 30_reliability.sh [spec] [pg og]
source "$(dirname "$0")/lib/common.sh"

SPEC="${1:-$SERVER_SPEC}"
DBS="${2:-pg og}"
CRASH_REPS="${CRASH_REPS:-5}"
BULK_REPS="${BULK_REPS:-3}"
vm_use_spec "$SPEC"
record_env
OUT="$(out_host reliability)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# kill_and_recover <db> <operation> <rep>: kill -9 the server, restart it, time until it answers.
kill_and_recover() {
  local db="$1" name; name="$(db_container "$db")"
  docker kill -s KILL "$name" >/dev/null
  java_host db-wait $(conn_host "$db") --pre-cmd "docker start $name" --timeout-sec 300 \
    --experiment crash --operation "$2" --variant recovery --rep "$3" $(ctx host tuned m) --out "$OUT"
}

for db in $DBS; do
  name="$(db_container "$db")"
  cm="$(ctx host tuned m)"; cl="$(ctx host tuned l)"
  log "== $db on $SPEC (host load $(host_load))"
  db_start "$db" tuned
  java_host db-info $(conn_host "$db") > "$(logs_dir)/settings_${db}_tuned.txt"

  log "$db: concurrent increments of one row"
  for mode in atomic naive forupdate repeatable; do
    java_host db-lostupdate $(conn_host "$db") --mode "$mode" --threads 8 --iters 1000 --reps 3 $cm --out "$OUT"
  done
  log "$db: concurrent inserts of the same key"
  java_host db-uniquerace $(conn_host "$db") --threads 16 --rounds 300 $cm --out "$OUT"
  log "$db: error messages"
  java_host db-errors $(conn_host "$db") $cm --out "$OUT" | tee "$(logs_dir)/errors_$db.txt"
  log "$db: connection limit"
  java_host db-connlimit $(conn_host "$db") --cap 1500 $cm --out "$OUT"

  log "$db: kill -9 under write load"
  for rep in $(seq 0 $((CRASH_REPS - 1))); do
    java_host db-ledger-run $(conn_host "$db") --threads 8 --acked-file "$TMP/acked" --max-sec 90 $cm > "$TMP/ledger.log" &
    pid=$!
    until grep -q RUNNING "$TMP/ledger.log" 2>/dev/null; do sleep 0.2; done
    sleep 8
    kill_and_recover "$db" write_load "$rep"
    wait "$pid" || true
    java_host db-ledger-verify $(conn_host "$db") --acked-file "$TMP/acked" --rep "$rep" $cm --out "$OUT"
  done

  log "$db: kill -9 in the middle of one bulk UPDATE"
  java_host db-bulk-start $(conn_host "$db") $cm | tee "$TMP/bulk.log"
  full_ms="$(sed -n 's/.*COMPLETED.* ms=\([0-9]*\).*/\1/p' "$TMP/bulk.log")"
  [ -n "$full_ms" ] || die "could not time the bulk update"
  for rep in $(seq 0 $((BULK_REPS - 1))); do
    java_host db-bulk-start $(conn_host "$db") $cm > "$TMP/bulk.log" &
    pid=$!
    until grep -q READY "$TMP/bulk.log" 2>/dev/null; do sleep 0.1; done
    sleep "$(python3 -c "print($full_ms * 0.25 / 1000)")"     # a quarter into the statement (the kill itself takes time)
    kill_and_recover "$db" bulk_update "$rep"
    wait "$pid" || true
    cat "$TMP/bulk.log"
    # kill_landed = 0 means the statement had already committed, so its rows are rightly kept.
    csv_row reliability "$(db_label "$db")" tuned m crash bulk_update kill9 "$rep" kill_landed \
      "$(grep -q INTERRUPTED "$TMP/bulk.log" && echo 1 || echo 0)" bool
    java_host db-bulk-verify $(conn_host "$db") --rep "$rep" $cm --out "$OUT"
  done

  log "$db: heavy analytical queries"
  java_host db-heavy $(conn_host "$db") --timeout-sec 300 $cl --out "$OUT" || true

  log "$db: sustained load for $SOAK_SECONDS s"
  oom0="$(vm_oom_kills)"; cpu0="$(cpu_usec "$name")"
  java_host db-load $(conn_host "$db") --workload mixed --clients 16 --duration-sec "$SOAK_SECONDS" --warmup-sec 10 \
    --series-sec 30 --experiment soak $cl --out "$OUT"
  cpu1="$(cpu_usec "$name")"
  csv_row reliability "$(db_label "$db")" tuned l soak mixed c16 0 server_cpu_cores \
    "$(python3 -c "print(round(($cpu1 - $cpu0) / 1e6 / ($SOAK_SECONDS + 10), 3))")" cores
  csv_row reliability "$(db_label "$db")" tuned l soak mixed c16 0 server_mem_peak_mb "$(mem_mb "$name" peak)" MB
  csv_row reliability "$(db_label "$db")" tuned l soak mixed c16 0 server_mem_end_mb "$(mem_mb "$name" current)" MB
  csv_row reliability "$(db_label "$db")" tuned l soak mixed c16 0 vm_oom_kills "$(( $(vm_oom_kills) - oom0 ))" count
  db_stop "$db"
done
log "reliability experiment finished"
