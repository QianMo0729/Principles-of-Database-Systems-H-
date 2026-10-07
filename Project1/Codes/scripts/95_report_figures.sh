#!/usr/bin/env bash
# Draw the figures used by the written report: no title block inside the figure, because the LaTeX
# caption carries it. English labels go to Report/figures, Chinese labels to Report/figures_zh.
# The report uses the English set. Build it with   cd Report && latexmk -xelatex report.tex
source "$(dirname "$0")/lib/common.sh"

docker info >/dev/null 2>&1 || vm_use_spec "$STANDARD_SPEC"
docker build -q -t dbbench-analysis "$CODES_DIR/analysis" >/dev/null
for lang in en zh; do
  if [ "$lang" = en ]; then dir=figures; else dir=figures_zh; fi
  mkdir -p "$PROJ_DIR/Report/$dir"
  log "report figures ($lang) -> Report/$dir"
  docker run --rm -e FIG_REPORT="$lang" -e FIG_OUT="/work/report/$dir" \
    -v "$CODES_DIR/analysis:/work/analysis:ro" -v "$OUTPUT_DIR:/work/output" -v "$PROJ_DIR/Report:/work/report" \
    dbbench-analysis python analysis/plot.py output
done
