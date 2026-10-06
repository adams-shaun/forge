#!/usr/bin/env bash
# Host, heavy lock: install forge-core, forge-game and forge-ai from the fork
# checkout this script lives in into $FORGE_ORACLE_DIR/m2, then write cp.txt.
#   gorge/scripts/heavy.sh heavy --mem 7G --wait 3600 --name forge-oracle-build -- forge-oracle/scripts/build-upstream.sh
set -euo pipefail
. "$(dirname "$0")/env.sh"
FORK=$(cd "$FORGE_ORACLE_SRC/.." && pwd)
export JAVA_HOME=$FORGE_ORACLE_JDK MAVEN_OPTS=${MAVEN_OPTS:--Xmx2g}
cd "$FORK"
"$FORGE_ORACLE_MVN" -B -T 2 -pl forge-core,forge-game,forge-ai -am install -DskipTests -Dmaven.repo.local="$FORGE_ORACLE_DIR/m2"
exec "$(dirname "$0")/cp.sh"
