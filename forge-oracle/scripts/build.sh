#!/usr/bin/env bash
# P0 build of forge-core, forge-game, forge-ai into a local m2 (host, heavy lock).
set -euo pipefail
D=/mnt/sata/gorge-training/forgeoracle
export JAVA_HOME=/mnt/sata/gorge-training/xmageoracle/jdk
export PATH=$JAVA_HOME/bin:/mnt/sata/gorge-training/xmageoracle/maven/bin:$PATH
export MAVEN_OPTS="-Xmx2g"
cd $D/forge
start=$(date +%s)
/usr/bin/time -v mvn -B -T 2 -pl forge-core,forge-game,forge-ai -am install -DskipTests -Dmaven.repo.local=$D/m2 "$@"
end=$(date +%s)
echo "build_seconds=$((end-start))" > $D/build.time
# classpath of forge-ai (core+game+their deps) from the local repo
mvn -B -q -pl forge-ai dependency:build-classpath -Dmdep.outputFile=$D/cp.deps.txt -Dmaven.repo.local=$D/m2
V=$(ls $D/m2/forge/forge-ai/)
echo "$D/m2/forge/forge-ai/$V/forge-ai-$V.jar:$(cat $D/cp.deps.txt)" > $D/cp.txt
echo "cp written: $(tr ':' '\n' < $D/cp.txt | wc -l) entries"
