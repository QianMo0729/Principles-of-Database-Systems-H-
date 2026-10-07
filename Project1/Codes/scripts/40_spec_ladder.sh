#!/usr/bin/env bash
# Experiment D (cost): which machine does each DBMS need?
#
# The VM is resized to each spec in turn (the whole Linux machine, including kernel and Docker,
# gets exactly that CPU and memory). For both systems, with the image's default settings and with
# settings sized for the spec, the script records:
#   1. fresh install: can a brand-new instance initialise and start on this machine?
#   2. start with the full data set already on disk, and memory used when idle
#      ("default" = the settings the instance was installed with on the standard spec, unchanged;
#       "tuned" = settings sized for the machine at hand);
#   3. a business-style load (16 clients, 80 % reads / 20 % writes) with latency and failures;
#   4. two heavy analytical queries;
#   5. whether the server was still alive afterwards and whether the kernel had to kill anything.
# Usage: 40_spec_ladder.sh [spec ...]
source "$(dirname "$0")/lib/common.sh"

SPECS="${*:-$LADDER_SPECS}"
LADDER_LOAD_SECONDS="${LADDER_LOAD_SECONDS:-30}"

row() { csv_row ladder "$@"; }   # row <target> <config> <scale> <experiment> <operation> <variant> <rep> <metric> <value> <unit> [note]

fresh_install() {
  local db="$1" label rc=0 conn
  label="$(db_label "$db")"
  docker rm -f bench-fresh >/dev/null 2>&1 || true
  if [ "$db" = pg ]; then
    docker run -d --name bench-fresh -p 15440:5432 -e POSTGRES_PASSWORD="$DB_PASSWORD" "$PG_IMAGE" >/dev/null
    conn="--target pg --host $VM_IP --port 15440 --user $PG_USER --password $DB_PASSWORD"
  else
    docker run -d --name bench-fresh -p 15440:5432 --privileged=true -e GS_PASSWORD="$DB_PASSWORD" "$OG_IMAGE" >/dev/null
    conn="--target og --host $VM_IP --port 15440 --user $OG_USER --password $DB_PASSWORD"
  fi
  java_host db-wait $conn --timeout-sec 120 --experiment ladder --operation fresh_install --variant ready \
    $(ctx host default none) --out "$(out_host ladder)" || rc=$?
  if [ "$rc" = 0 ]; then
    sleep 5
    row "$label" default none ladder fresh_install ready 0 idle_mem_mb "$(mem_mb bench-fresh current)" MB
  else
    docker logs --tail 8 bench-fresh > "$(logs_dir)/fresh_install_${db}_failure.txt" 2>&1 || true
    row "$label" default none ladder fresh_install ready 0 failure_log 0 text "$(tail -3 "$(logs_dir)/fresh_install_${db}_failure.txt" | cut -c1-300)"
  fi
  docker rm -f bench-fresh >/dev/null 2>&1 || true
}

with_data() {
  local db="$1" cfg="$2" label name rc=0 c oom0 cpu0 cpu1
  label="$(db_label "$db")"; name="$(db_container "$db")"; c="$(ctx host "$cfg" l)"
  oom0="$(vm_oom_kills)"
  db_run "$db" "$cfg"
  java_host db-wait $(conn_host "$db") --timeout-sec 150 --experiment ladder --operation start --variant ready \
    $c --out "$(out_host ladder)" || rc=$?
  if [ "$rc" != 0 ]; then
    docker logs --tail 12 "$name" > "$(logs_dir)/start_${db}_${cfg}_failure.txt" 2>&1 || true
    row "$label" "$cfg" l ladder start ready 0 failure_log 0 text "$(grep -iE 'fatal|error|memory|killed|cannot|could not' "$(logs_dir)/start_${db}_${cfg}_failure.txt" | tail -2 | cut -c1-300)"
    row "$label" "$cfg" l ladder start ready 0 vm_oom_kills "$(( $(vm_oom_kills) - oom0 ))" count
    row "$label" "$cfg" l ladder start ready 0 exit_code "$(docker inspect -f '{{.State.ExitCode}}' "$name" 2>/dev/null || echo -1)" code
    db_stop "$db"
    return
  fi
  sleep 5
  row "$label" "$cfg" l ladder start ready 0 idle_mem_mb "$(mem_mb "$name" current)" MB
  row "$label" "$cfg" l ladder start ready 0 vm_available_idle_mb "$(vm_meminfo MemAvailable)" MB

  cpu0="$(cpu_usec "$name")"
  java_host db-load $(conn_host "$db") --workload mixed --clients 16 --duration-sec "$LADDER_LOAD_SECONDS" --warmup-sec 10 \
    --experiment ladder $c --out "$(out_host ladder)" || true
  cpu1="$(cpu_usec "$name")"
  row "$label" "$cfg" l ladder mixed c16 0 server_cpu_cores \
    "$(python3 -c "print(round((${cpu1:-0} - ${cpu0:-0}) / 1e6 / ($LADDER_LOAD_SECONDS + 10), 3))")" cores
  row "$label" "$cfg" l ladder mixed c16 0 server_mem_mb "$(mem_mb "$name" current)" MB
  row "$label" "$cfg" l ladder mixed c16 0 vm_available_mb "$(vm_meminfo MemAvailable)" MB

  java_host db-heavy $(conn_host "$db") --timeout-sec 150 --experiment ladder_heavy $c --out "$(out_host ladder)" || true

  row "$label" "$cfg" l ladder survive after_tests 0 server_mem_peak_mb "$(mem_mb "$name" peak)" MB
  row "$label" "$cfg" l ladder survive after_tests 0 still_running \
    "$([ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" = true ] && echo 1 || echo 0)" bool
  row "$label" "$cfg" l ladder survive after_tests 0 vm_oom_kills "$(( $(vm_oom_kills) - oom0 ))" count
  db_stop "$db"
}

for spec in $SPECS; do
  vm_use_spec "$spec"
  record_env
  rm -f "$(out_host ladder)"
  log "== spec $spec (host load $(host_load))"
  row none none none ladder machine no_database 0 vm_total_mb "$(vm_meminfo MemTotal)" MB
  row none none none ladder machine no_database 0 vm_available_mb "$(vm_meminfo MemAvailable)" MB
  for db in pg og; do
    log "$spec $db: fresh install"
    fresh_install "$db"
    for cfg in default tuned; do
      log "$spec $db: full data, $cfg config"
      with_data "$db" "$cfg"
    done
  done
done
log "spec ladder finished"
