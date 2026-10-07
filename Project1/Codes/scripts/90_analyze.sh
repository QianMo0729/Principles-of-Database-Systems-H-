#!/usr/bin/env bash
# Turn the raw measurements of every VM spec into summary tables and figures (output/summary).
# Runs in a small Python container, so nothing has to be installed on the host.
source "$(dirname "$0")/lib/common.sh"

docker info >/dev/null 2>&1 || vm_use_spec "$STANDARD_SPEC"
docker build -q -t dbbench-analysis "$CODES_DIR/analysis" >/dev/null
docker run --rm -v "$CODES_DIR/analysis:/work/analysis:ro" -v "$OUTPUT_DIR:/work/output" dbbench-analysis \
  sh -c 'python analysis/summarize.py output && python analysis/plot.py output'
