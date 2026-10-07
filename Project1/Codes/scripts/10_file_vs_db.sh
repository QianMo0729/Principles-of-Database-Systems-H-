#!/usr/bin/env bash
# Experiment A: data operations in files versus the same operations through a DBMS.
#
# Everything runs inside the VM at the standard spec: the Java file programs, the JDBC client and
# the database share the same Linux kernel, CPUs and disk, so only the method differs.
# Usage: 10_file_vs_db.sh [file] [pg] [og] [pg-default] [og-default]   (no argument = all parts)
#        extra parts for redoing one piece: file-index, pg-parallel, og-parallel
source "$(dirname "$0")/lib/common.sh"

vm_use_spec "$STANDARD_SPEC"
record_env
PLANS="/out/vm-$CURRENT_SPEC/logs/plans"
PARTS="${*:-file pg og pg-default og-default}"

reps_for() { if [ "$1" = l ]; then echo 3; else echo "$REPS"; fi; }   # heavy update: fewer runs on the full data

file_part() {
  log "host load before file part: $(host_load)"
  for scale in $SCALES; do
    local c meta="/data/clean/meta_$scale.properties"
    c="$(ctx vm na "$scale")"
    log "file retrieval, scale $scale"
    java_vm file-search --file "/data/clean/titles_$scale.tsv" --meta "$meta" --reps "$REPS" --warmup "$WARMUP" $c --out "$(out_vm search)"
    java_vm file-index --file "/data/clean/titles_$scale.tsv" --meta "$meta" --reps "$REPS" $c --out "$(out_vm search)"
    log "file update, scale $scale"
    for rule in broad narrow; do
      for mode in safe nofsync; do
        java_vm file-update --file "/data/clean/people_$scale.tsv" --work /data/work/people_work.tsv --meta "$meta" \
          --rule "$rule" --mode "$mode" --reps "$REPS" $c --out "$(out_vm update)"
      done
    done
  done

  local c meta=/data/clean/meta_m.properties
  c="$(ctx vm na m)"
  log "file concurrency: lost updates"
  for mode in nolock lock lock_fsync; do
    java_vm file-counter --work /data/work/counter.dat --mode "$mode" --threads 8 --iters 2000 --reps 3 $c --out "$(out_vm reliability)"
  done
  log "file crash in the middle of a bulk update"
  for mode in inplace safe; do
    java_vm file-update --file /data/clean/people_m.tsv --work /data/work/people_crash.tsv --meta "$meta" \
      --rule broad --mode "$mode" --reps 1 --crash-at 0.5 $c --out "$(out_vm update)" || true   # exits 137 by design
    java_vm file-check --file /data/clean/people_m.tsv --work /data/work/people_crash.tsv --variant "$mode" $c --out "$(out_vm reliability)"
  done
}

# search_variants <db> <config> <scale> <variants...>: build each index configuration, then query.
search_variants() {
  local db="$1" cfg="$2" scale="$3" c rc; shift 3
  c="$(ctx vm "$cfg" "$scale")"
  for variant in "$@"; do
    rc=0
    java_vm db-index $(conn_vm "$db") --variant "$variant" $c --out "$(out_vm setup)" || rc=$?
    if [ "$rc" = 3 ]; then continue; fi          # this database cannot build that index; recorded
    [ "$rc" = 0 ] || die "db-index $db $variant failed ($rc)"
    java_vm db-search $(conn_vm "$db") --variant "$variant" --meta "/data/clean/meta_$scale.properties" \
      --reps "$REPS" --warmup "$WARMUP" --plans-dir "$PLANS" $c --out "$(out_vm search)"
  done
  java_vm db-index $(conn_vm "$db") --variant noidx $c --out "$(out_vm setup)"
}

# Full scans again with each system's parallel query switched the other way: PostgreSQL uses
# 2 parallel workers by default, openGauss runs single-threaded unless query_dop is raised.
parallel_part() {
  local db="$1" c="$2" label sql
  if [ "$db" = pg ]; then label=noidx_serial; sql="SET max_parallel_workers_per_gather = 0"
  else label=noidx_dop3; sql="SET query_dop = 3"; fi
  log "$db full scans with: $sql"
  java_vm db-search $(conn_vm "$db") --variant noidx --label "$label" --session-sql "$sql" \
    --meta /data/clean/meta_l.properties --reps "$REPS" --warmup "$WARMUP" --plans-dir "$PLANS" $c --out "$(out_vm search)" \
    || log "WARNING: $db rejected '$sql'"
}

db_part() {
  local db="$1" c
  log "starting $db (tuned); host load $(host_load)"
  db_start "$db" tuned
  java_host db-info $(conn_host "$db") > "$(logs_dir)/settings_${db}_tuned.txt"
  for scale in $SCALES; do
    c="$(ctx vm tuned "$scale")"
    log "$db load, scale $scale"
    java_vm db-setup $(conn_vm "$db") --data-dir /data/clean $c --out "$(out_vm setup)"
    log "$db retrieval, scale $scale"
    search_variants "$db" tuned "$scale" noidx btree trgm fts
    if [ "$scale" = l ]; then parallel_part "$db" "$c"; fi
    log "$db update, scale $scale"
    for rule in broad narrow; do
      java_vm db-update $(conn_vm "$db") --meta "/data/clean/meta_$scale.properties" --rule "$rule" --storage heap \
        --reps "$(reps_for "$scale")" $c --out "$(out_vm update)"
      if [ "$db" = og ]; then
        java_vm db-update $(conn_vm "$db") --meta "/data/clean/meta_$scale.properties" --rule "$rule" --storage ustore \
          --reps "$(reps_for "$scale")" $c --out "$(out_vm update)"
      fi
    done
  done
  db_stop "$db"
}

# The same full-scale workload with each product's out-of-the-box settings.
db_default_part() {
  local db="$1" c
  log "starting $db (default config); host load $(host_load)"
  db_start "$db" default
  java_host db-info $(conn_host "$db") > "$(logs_dir)/settings_${db}_default.txt"
  c="$(ctx vm default l)"
  search_variants "$db" default l noidx btree
  java_vm db-update $(conn_vm "$db") --meta /data/clean/meta_l.properties --rule broad --storage heap --reps 3 $c --out "$(out_vm update)"
  db_stop "$db"
}

# Parts that repeat one piece of a full run on its own (used to resume or redo that piece).
file_index_part() {
  for scale in $SCALES; do
    java_vm file-index --file "/data/clean/titles_$scale.tsv" --meta "/data/clean/meta_$scale.properties" --reps "$REPS" \
      $(ctx vm na "$scale") --out "$(out_vm search)"
  done
}

parallel_only_part() {
  local db="$1" c
  c="$(ctx vm tuned l)"
  db_start "$db" tuned
  java_vm db-index $(conn_vm "$db") --variant noidx $c --out "$(out_vm setup)"
  parallel_part "$db" "$c"
  db_stop "$db"
}

for part in $PARTS; do
  case "$part" in
    file) file_part ;;
    file-index) file_index_part ;;
    pg-parallel) parallel_only_part pg ;;
    og-parallel) parallel_only_part og ;;
    pg|og) db_part "$part" ;;
    pg-default) db_default_part pg ;;
    og-default) db_default_part og ;;
    *) die "unknown part: $part" ;;
  esac
done
log "experiment A finished; host load $(host_load)"
