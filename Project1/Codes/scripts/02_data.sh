#!/usr/bin/env bash
# Get the IMDb dumps into the VM's data volume and build the cleaned s / m / l files.
#
# The data never touches the project folder: it is large, and IMDb's licence (personal and
# non-commercial use) does not allow redistributing it, so only this script is published.
# Set IMDB_LOCAL_DIR to a folder that already holds the *.tsv.gz files to skip the download.
source "$(dirname "$0")/lib/common.sh"

vm_use_spec "$STANDARD_SPEC"
docker volume create "$DATA_VOL" >/dev/null
docker rm -f dbbench-data >/dev/null 2>&1 || true
docker run -d --name dbbench-data -v "$DATA_VOL:/data" "$JDK_IMAGE" sleep 7200 >/dev/null
docker exec dbbench-data mkdir -p /data/raw /data/clean /data/work

for f in $IMDB_FILES; do
  if docker exec dbbench-data test -s "/data/raw/$f.tsv.gz"; then
    log "$f.tsv.gz already present"
  elif [ -n "${IMDB_LOCAL_DIR:-}" ] && [ -s "$IMDB_LOCAL_DIR/$f.tsv.gz" ]; then
    log "copying $f.tsv.gz from $IMDB_LOCAL_DIR"
    docker cp "$IMDB_LOCAL_DIR/$f.tsv.gz" "dbbench-data:/data/raw/$f.tsv.gz"
  else
    log "downloading $f.tsv.gz"
    tmp="$(mktemp -d)"   # system temp folder, outside the (cloud-synced) project
    curl -fsSL --retry 3 -o "$tmp/$f.tsv.gz" "$IMDB_BASE_URL/$f.tsv.gz"
    docker cp "$tmp/$f.tsv.gz" "dbbench-data:/data/raw/$f.tsv.gz"
    rm -rf "$tmp"
  fi
done
docker exec dbbench-data sh -c 'ls -l /data/raw' | tee "$(logs_dir)/data_raw_files.txt"
docker rm -f dbbench-data >/dev/null

log "cleaning and sampling"
rm -f "$(out_host prep)"
java_vm prep --raw-dir /data/raw --out-dir /data/clean $(ctx vm na all) --out "$(out_vm prep)" \
  | tee "$(logs_dir)/data_prep.txt"
docker run --rm -v "$DATA_VOL:/data" "$JDK_IMAGE" sh -c 'ls -l /data/clean; echo; cat /data/clean/meta_l.properties' \
  | tee -a "$(logs_dir)/data_prep.txt"
