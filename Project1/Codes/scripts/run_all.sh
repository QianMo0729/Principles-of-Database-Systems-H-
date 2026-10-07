#!/usr/bin/env bash
# Run the whole study from an installed container runtime to finished tables and figures.
# Each step can also be run on its own; see README.md for what it measures and how long it takes.
source "$(dirname "$0")/lib/common.sh"

S="$SCRIPTS_DIR"
run() {   # run <log-spec> <script> [args...]: keep each step's console output next to its results
  local spec="$1" script="$2"; shift 2
  mkdir -p "$OUTPUT_DIR/vm-$spec/logs"
  log ">>> $script $*"
  caffeinate -ims bash "$S/$script" "$@" > "$OUTPUT_DIR/vm-$spec/logs/${script%.sh}.log" 2>&1 \
    || die "$script failed, see $OUTPUT_DIR/vm-$spec/logs/${script%.sh}.log"
}

bash "$S/01_build.sh"
run "$STANDARD_SPEC" 02_data.sh
run "$STANDARD_SPEC" 10_file_vs_db.sh
run "$STANDARD_SPEC" 20_pg_vs_og_load.sh
run "$SERVER_SPEC"   30_reliability.sh
run "$STANDARD_SPEC" 40_spec_ladder.sh     # writes into every output/vm-<spec>; its own log sits with the standard spec
run "$STANDARD_SPEC" 45_small_spec_diagnosis.sh
bash "$S/90_analyze.sh"
colima stop
log "all done: see $OUTPUT_DIR/summary"
