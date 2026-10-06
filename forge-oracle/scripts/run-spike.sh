#!/usr/bin/env bash
# run-spike.sh IN OUT [args...]  (cwd-independent)
set -euo pipefail
D=/mnt/sata/gorge-training/forgeoracle
export FORGE_RES=${FORGE_RES:-$D/forge/forge-gui/res}
export FORGE_ORACLE_REF=${FORGE_ORACLE_REF:-$(git -C $D/forge rev-parse HEAD)}
exec /mnt/sata/gorge-training/xmageoracle/jdk/bin/java -Djava.awt.headless=true -Xmx${XMX:-1536m} -XX:+UseSerialGC \
  -Djava.io.tmpdir=$D/tmp -cp "$D/classes:$(cat $D/cp.txt)" forge.oracle.spike.SpikeMain "$@"
