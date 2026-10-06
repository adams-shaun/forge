#!/usr/bin/env bash
# Write $FORGE_ORACLE_DIR/cp.txt: the three installed module jars, the reactor
# classpath of forge-ai (core + game + their third-party jars) and gson (not a
# dependency of core/game/ai; the driver's JSON). The installed POMs keep
# ${revision} unflattened, so the classpath must come from the reactor.
set -euo pipefail
. "$(dirname "$0")/env.sh"
FORK=$(cd "$FORGE_ORACLE_SRC/.." && pwd)
export JAVA_HOME=$FORGE_ORACLE_JDK MAVEN_OPTS=${MAVEN_OPTS:--Xmx1g}
cd "$FORK"
"$FORGE_ORACLE_MVN" -B -q -pl forge-ai -am dependency:build-classpath -Dmdep.outputFile=target/cp.deps.txt -Dmaven.repo.local="$FORGE_ORACLE_DIR/m2"
V=$("$FORGE_ORACLE_MVN" -B -q -N help:evaluate -Dexpression=revision -DforceStdout -Dmaven.repo.local="$FORGE_ORACLE_DIR/m2" 2>/dev/null || true)
[ -n "$V" ] || V=$(ls "$FORGE_ORACLE_DIR/m2/forge/forge-ai/")
J=$FORGE_ORACLE_DIR/m2/forge
GSON=$FORGE_ORACLE_DIR/m2/com/google/code/gson/gson/2.13.1/gson-2.13.1.jar
[ -f "$GSON" ] || GSON=$FORGE_ORACLE_DIR/lib/gson-2.13.1.jar
echo "$J/forge-core/$V/forge-core-$V.jar:$J/forge-game/$V/forge-game-$V.jar:$J/forge-ai/$V/forge-ai-$V.jar:$(cat forge-ai/target/cp.deps.txt):$GSON" > "$FORGE_ORACLE_DIR/cp.txt"
echo "cp.txt: $(tr ':' '\n' < "$FORGE_ORACLE_DIR/cp.txt" | wc -l) entries"
