#!/usr/bin/env bash
# Experiment B (performance): PostgreSQL versus openGauss under concurrent clients.
#
# The load generator runs on the host and reaches the database over the VM's network address, so
# it does not use any of the VM's CPU or memory. Both systems hold the full data set, use the
# configuration aligned for the spec, and take turns round by round (pg, og / og, pg / ...), so
# drift or disturbance on the host affects both alike.
source "$(dirname "$0")/lib/common.sh"

SPEC="${1:-$STANDARD_SPEC}"
ROUNDS="${ROUNDS:-3}"
vm_use_spec "$SPEC"
record_env

block() {
  local db="$1" round="$2" name c
  name="$(db_container "$db")"
  c="$(ctx host tuned l)"
  db_start "$db" tuned
  # Untimed pass so both systems start each round with a warm cache.
  java_host db-load $(conn_host "$db") --workload read --clients 16 --duration-sec 15 --warmup-sec 1 \
    --experiment warmup $c --out "$(out_host _warmup)" >/dev/null
  for workload in read mixed; do
    for clients in $LOAD_CLIENTS; do
      local cpu0 cpu1 load0
      cpu0="$(cpu_usec "$name")"; load0="$(host_load)"
      java_host db-load $(conn_host "$db") --workload "$workload" --clients "$clients" --rep "$round" \
        --duration-sec "$LOAD_SECONDS" --warmup-sec "$LOAD_WARMUP_SECONDS" $c --out "$(out_host load)"
      cpu1="$(cpu_usec "$name")"
      csv_row load "$(db_label "$db")" tuned l load "$workload" "c$clients" "$round" server_cpu_cores \
        "$(python3 -c "print(round(($cpu1 - $cpu0) / 1e6 / ($LOAD_SECONDS + $LOAD_WARMUP_SECONDS), 3))")" cores
      csv_row load "$(db_label "$db")" tuned l load "$workload" "c$clients" "$round" server_mem_mb "$(mem_mb "$name" current)" MB
      csv_row load "$(db_label "$db")" tuned l load "$workload" "c$clients" "$round" host_load_before "$load0" load
    done
  done
  db_stop "$db"
}

for round in $(seq 0 $((ROUNDS - 1))); do
  if [ $((round % 2)) = 0 ]; then order="pg og"; else order="og pg"; fi
  for db in $order; do
    log "round $round: $db (host load $(host_load))"
    block "$db" "$round"
  done
done
rm -f "$(out_host _warmup)"
log "load experiment finished"
