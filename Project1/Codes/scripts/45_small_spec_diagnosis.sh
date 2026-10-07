#!/usr/bin/env bash
# Follow-up to the spec ladder: record *why* a server does not run on a small machine, and which
# memory settings a brand-new install chooses for itself there. Writes text files to
# output/vm-<spec>/logs so the explanation in the report rests on recorded evidence.
# Usage: 45_small_spec_diagnosis.sh [start-spec] [fresh-spec]
source "$(dirname "$0")/lib/common.sh"

START_SPEC="${1:-1c512m}"   # where openGauss does not start even with settings sized for the machine
FRESH_SPEC="${2:-1c1g}"     # the smallest machine on which both systems install

# What happens when openGauss is started on its existing data with the sized settings.
vm_use_spec "$START_SPEC"
db_run og tuned
sleep 25
{
  echo "spec: $START_SPEC, openGauss started on the full data set with settings sized for the machine"
  echo "container state: $(docker inspect -f 'running={{.State.Running}} exit_code={{.State.ExitCode}} oom_killed={{.State.OOMKilled}}' "$OG_CONTAINER")"
  echo "VM memory (MiB): total $(vm_meminfo MemTotal), available $(vm_meminfo MemAvailable); kernel OOM kills: $(vm_oom_kills)"
  echo "--- last lines of the server log"
  docker logs --tail 15 "$OG_CONTAINER" 2>&1 | cut -c1-300
  echo "--- last kernel messages"
  colima ssh -- sudo dmesg | tail -8
} > "$(logs_dir)/diagnosis_og_start.txt" 2>&1
db_stop og
cat "$(logs_dir)/diagnosis_og_start.txt"

# The settings a new install picks on a small machine, for both systems.
vm_use_spec "$FRESH_SPEC"
for db in pg og; do
  docker rm -f bench-fresh >/dev/null 2>&1 || true
  if [ "$db" = pg ]; then
    docker run -d --name bench-fresh -p 15440:5432 -e POSTGRES_PASSWORD="$DB_PASSWORD" "$PG_IMAGE" >/dev/null
    conn="--target pg --host $VM_IP --port 15440 --user $PG_USER --password $DB_PASSWORD"
  else
    docker run -d --name bench-fresh -p 15440:5432 --privileged=true -e GS_PASSWORD="$DB_PASSWORD" "$OG_IMAGE" >/dev/null
    conn="--target og --host $VM_IP --port 15440 --user $OG_USER --password $DB_PASSWORD"
  fi
  if java_host db-wait $conn --timeout-sec 120; then
    java_host db-info $conn > "$(logs_dir)/settings_${db}_fresh_install.txt"
    grep -E 'shared_buffers|max_connections|max_process_memory|cstore_buffers' "$(logs_dir)/settings_${db}_fresh_install.txt" | sed "s/^/$db fresh install on $FRESH_SPEC: /"
  fi
  docker rm -f bench-fresh >/dev/null 2>&1 || true
done

vm_use_spec "$STANDARD_SPEC"   # leave the VM at the standard spec for the analysis step
