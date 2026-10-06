#!/usr/bin/env bash
# Compile the driver (main sources only) against cp.txt into OUT
# (default: forge-oracle/target/classes, inside the checkout, so a jailed seat can write it).
set -euo pipefail
. "$(dirname "$0")/env.sh"
OUT=${1:-$FORGE_ORACLE_SRC/target/classes}
rm -rf "$OUT"; mkdir -p "$OUT"
"$JAVAC" -J-Xmx1g --release 17 -nowarn -encoding UTF-8 -cp "$(cat "$FORGE_ORACLE_DIR/cp.txt")" -d "$OUT" \
  $(find "$FORGE_ORACLE_SRC/src/main/java" -name '*.java' | sort)
