#!/usr/bin/env bash
# Compile the Java sources into Codes/java/build/classes (plain javac, no build tool needed).
source "$(dirname "$0")/lib/common.sh"

BUILD="$CODES_DIR/java/build/classes"
rm -rf "$BUILD" && mkdir -p "$BUILD"
# One quoted path per line: the project path contains spaces.
find "$CODES_DIR/java/src" -name '*.java' | sed 's/.*/"&"/' > "$CODES_DIR/java/build/sources.txt"
# --release 21 so the same classes run on the host JDK and on the JDK image inside the VM.
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "$CODES_DIR/java/lib/*" -d "$BUILD" \
  @"$CODES_DIR/java/build/sources.txt"
log "compiled $(wc -l < "$CODES_DIR/java/build/sources.txt" | tr -d ' ') source files into $BUILD"
