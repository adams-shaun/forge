#!/usr/bin/env bash
# run.sh IN OUT [--repeat N]: replay requests through the driver compiled in
# forge-oracle/target/classes (scripts/javac.sh). Cards come from this fork
# checkout's forge-gui/res; forge_ref is its HEAD. Wrap it in a scope:
#   systemd-run --user --scope -q -p MemoryMax=2G -p CPUQuota=200% forge-oracle/scripts/run.sh in.jsonl out.jsonl
set -euo pipefail
. "$(dirname "$0")/env.sh"
FORK=$(cd "$FORGE_ORACLE_SRC/.." && pwd)
CLASSES=${FORGE_ORACLE_CLASSES:-$FORGE_ORACLE_SRC/target/classes}
TMPD=${FORGE_ORACLE_TMP:-$FORGE_ORACLE_SRC/target/tmp}
mkdir -p "$TMPD"
export FORGE_RES=${FORGE_RES:-$FORK/forge-gui/res}
export FORGE_ORACLE_REF=${FORGE_ORACLE_REF:-$(git -C "$FORK" rev-parse HEAD)}
exec "$JAVA" -Djava.awt.headless=true -Xmx${XMX:-1536m} -XX:+UseSerialGC -Djava.io.tmpdir="$TMPD" \
  -cp "$CLASSES:$(cat "$FORGE_ORACLE_DIR/cp.txt")" forge.oracle.ScenarioReplay "$@"
