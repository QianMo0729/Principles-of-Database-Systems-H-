#!/usr/bin/env bash
# Shared helpers for every experiment script. Written for bash 3.2 (the macOS default).
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODES_DIR="$(cd "$SCRIPTS_DIR/.." && pwd)"
PROJ_DIR="$(cd "$CODES_DIR/.." && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/output}"   # override to keep trial runs apart from real results
# shellcheck source=/dev/null
source "$CODES_DIR/config/experiment.env"

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

# ---------------------------------------------------------------- VM spec

# load_spec <spec>: read Codes/config/specs/<spec>.env into SPEC_* variables.
load_spec() {
  local f="$CODES_DIR/config/specs/$1.env"
  [ -f "$f" ] || die "unknown VM spec: $1"
  # shellcheck source=/dev/null
  source "$f"
}

vm_ip() {
  colima list -j 2>/dev/null | python3 -c 'import json,sys
for line in sys.stdin:
    d = json.loads(line)
    if d.get("name") == "default": print(d.get("address", ""))'
}

# vm_use_spec <spec>: make the VM have exactly the CPU count and memory of the spec.
# The VM disk (images, data volumes) is kept; only CPU and memory change, like resizing a server.
vm_use_spec() {
  load_spec "$1"
  local want cur
  want="Running $SPEC_CPU $(python3 -c "print(int(float('$SPEC_MEM_GB') * 1024 ** 3))")"
  cur="$(colima list -j 2>/dev/null | python3 -c 'import json,sys
for line in sys.stdin:
    d = json.loads(line)
    if d.get("name") == "default": print(d.get("status"), d.get("cpus"), d.get("memory"))' || true)"
  if [ "$cur" != "$want" ]; then
    log "switching VM to spec $1 (${SPEC_CPU} CPU, ${SPEC_MEM_GB} GiB)"
    if [ "${cur%% *}" = "Running" ]; then
      db_stop pg; db_stop og
      colima stop >/dev/null 2>&1
    fi
    colima start --cpu "$SPEC_CPU" --memory "$SPEC_MEM_GB" --vm-type vz --arch aarch64 \
      --mount-type virtiofs --network-address </dev/null >/dev/null 2>&1 || die "colima start failed for $1"
  fi
  VM_IP="$(vm_ip)"; export VM_IP
  [ -n "$VM_IP" ] || die "VM has no reachable address"
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  CURRENT_SPEC="$1"; export CURRENT_SPEC
  mkdir -p "$OUTPUT_DIR/vm-$1/raw" "$OUTPUT_DIR/vm-$1/logs"
}

# ---------------------------------------------------------------- databases

db_container() { if [ "$1" = pg ]; then echo "$PG_CONTAINER"; else echo "$OG_CONTAINER"; fi; }

# tuned_flags <pg|og>: server options sized for the current spec (needs load_spec first).
tuned_flags() {
  local common="-c shared_buffers=$SHARED_BUFFERS -c work_mem=$WORK_MEM -c maintenance_work_mem=$MAINT_WORK_MEM"
  common="$common -c max_connections=$MAX_CONNECTIONS -c checkpoint_timeout=15min"
  if [ "$1" = pg ]; then
    echo "$common -c wal_level=replica"
  else
    echo "$common -c wal_level=hot_standby -c max_process_memory=$OG_MAX_PROCESS_MEMORY -c cstore_buffers=16MB"
  fi
}

# db_run <pg|og> <default|tuned>: create the container on its data volume (does not wait).
db_run() {
  local db="$1" cfg="$2" name flags=""
  name="$(db_container "$db")"
  docker rm -f "$name" >/dev/null 2>&1 || true
  [ "$cfg" = tuned ] && flags="$(tuned_flags "$db")"
  # shellcheck disable=SC2086
  if [ "$db" = pg ]; then
    docker run -d --name "$name" --network "$NET" -p "$PG_PORT:5432" --shm-size=512m \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" -v "$PG_VOL:/var/lib/postgresql" "$PG_IMAGE" $flags >/dev/null
  else
    docker run -d --name "$name" --network "$NET" -p "$OG_PORT:5432" --shm-size=512m --privileged=true \
      -e GS_PASSWORD="$DB_PASSWORD" -v "$OG_VOL:/var/lib/opengauss" "$OG_IMAGE" $flags >/dev/null
  fi
}

# db_start <pg|og> <default|tuned> [timeout-sec]: run and wait until it answers queries from the host.
db_start() {
  db_run "$1" "$2"
  java_host db-wait $(conn_host "$1") --timeout-sec "${3:-180}"
}

db_stop() {
  local name; name="$(db_container "$1")"
  docker stop -t 120 "$name" >/dev/null 2>&1 || true
  docker rm -f "$name" >/dev/null 2>&1 || true
}

# Connection options for a client inside the VM / on the macOS host.
conn_vm() {
  if [ "$1" = pg ]; then echo "--target pg --host $PG_CONTAINER --port 5432 --user $PG_USER --password $DB_PASSWORD"
  else echo "--target og --host $OG_CONTAINER --port 5432 --user $OG_USER --password $DB_PASSWORD"; fi
}
conn_host() {
  if [ "$1" = pg ]; then echo "--target pg --host $VM_IP --port $PG_PORT --user $PG_USER --password $DB_PASSWORD"
  else echo "--target og --host $VM_IP --port $OG_PORT --user $OG_USER --password $DB_PASSWORD"; fi
}

# ---------------------------------------------------------------- running the Java program

# java_host <command> [options]: run on macOS; reaches the databases over the VM address.
java_host() {
  java -Xmx3g -cp "$CODES_DIR/java/build/classes:$CODES_DIR/java/lib/*" dbbench.Main "$@"
}

# java_vm <command> [options]: run in a JDK container inside the VM, next to the database and on
# the same disk. Paths: /data = data volume, /out = the project's output folder.
java_vm() {
  docker run --rm --network "$NET" -v "$CODES_DIR/java:/app:ro" -v "$OUTPUT_DIR:/out" -v "$DATA_VOL:/data" \
    "$JDK_IMAGE" java ${JAVA_VM_OPTS:--Xmx4g} -cp "/app/build/classes:/app/lib/*" dbbench.Main "$@"
}

# Context options every measurement carries: ctx <placement> <config> <scale>
ctx() { echo "--spec $CURRENT_SPEC --placement $1 --config $2 --scale $3"; }

# Result file for the current spec, as seen from the VM / from the host.
out_vm()   { echo "/out/vm-$CURRENT_SPEC/raw/$1.csv"; }
out_host() { echo "$OUTPUT_DIR/vm-$CURRENT_SPEC/raw/$1.csv"; }
logs_dir() { echo "$OUTPUT_DIR/vm-$CURRENT_SPEC/logs"; }

# csv_row <file-name> <target> <config> <scale> <experiment> <operation> <variant> <rep> <metric> <value> <unit> [note]
# Lets the shell record values it measures itself (memory, CPU time) in the same tidy format.
csv_row() {
  local f; f="$(out_host "$1")"
  [ -s "$f" ] || echo "run_ts,spec,placement,target,config,scale,experiment,operation,variant,rep,metric,value,unit,note" > "$f"
  local note="${12:-}"
  note="$(printf '%s' "$note" | tr '\n\r' '  ' | sed 's/"/""/g')"
  # placement "server": measured on the database side by the script, not by a client
  echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ'),$CURRENT_SPEC,server,$2,$3,$4,$5,$6,$7,$8,$9,${10},${11},\"$note\"" >> "$f"
}

# ---------------------------------------------------------------- resource snapshots

# cg <container> <file>: read one cgroup v2 accounting file of a running container.
cg() { docker exec "$1" cat "/sys/fs/cgroup/$2" 2>/dev/null || echo 0; }

# mem_mb <container> <current|peak>: memory charged to the container, in MiB.
mem_mb() { python3 -c "print(round($(cg "$1" "memory.$2" | head -1) / 1048576, 1))"; }

# cpu_usec <container>: CPU time consumed so far, in microseconds.
cpu_usec() { cg "$1" cpu.stat | awk '/^usage_usec/ {print $2}'; }

# vm_meminfo <field>: a /proc/meminfo value of the whole VM in MiB (e.g. MemAvailable).
vm_meminfo() { colima ssh -- cat /proc/meminfo 2>/dev/null | awk -v k="$1:" '$1 == k {printf "%.1f", $2 / 1024}'; }

# vm_oom_kills: how many processes the VM kernel has OOM-killed since boot.
vm_oom_kills() { colima ssh -- cat /proc/vmstat 2>/dev/null | awk '$1 == "oom_kill" {n = $2} END {print n + 0}'; }

# host_load: macOS 1-minute load average, recorded so disturbed runs can be recognised.
host_load() { sysctl -n vm.loadavg | awk '{print $2}'; }

# record_env: snapshot of the machine and software the current spec runs on.
record_env() {
  {
    echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "spec: $CURRENT_SPEC"
    echo "host: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB, macOS $(sw_vers -productVersion)"
    echo "host load (1 min): $(host_load)"
    echo "host java: $(java -version 2>&1 | head -1)"
    echo "colima: $(colima version | head -1)"
    colima list
    docker info --format 'docker {{.ServerVersion}}, {{.OperatingSystem}}, kernel {{.KernelVersion}}, {{.Architecture}}, cpus={{.NCPU}}, mem={{.MemTotal}}, cgroup v{{.CgroupVersion}}'
    colima ssh -- free -m
    for img in "$PG_IMAGE" "$OG_IMAGE" "$JDK_IMAGE"; do
      echo "image $img $(docker image inspect "$img" --format '{{.Id}} {{.Os}}/{{.Architecture}}')"
    done
  } > "$(logs_dir)/env.txt" 2>&1
}

# db_label <pg|og>: the target name used in result files.
db_label() { if [ "$1" = pg ]; then echo postgres; else echo opengauss; fi; }
