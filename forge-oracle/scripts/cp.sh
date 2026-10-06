#!/usr/bin/env bash
set -euo pipefail
D=/mnt/sata/gorge-training/forgeoracle
export JAVA_HOME=/mnt/sata/gorge-training/xmageoracle/jdk
export PATH=$JAVA_HOME/bin:/mnt/sata/gorge-training/xmageoracle/maven/bin:$PATH
export MAVEN_OPTS="-Xmx1g"
cd $D/forge
mvn -B -q -pl forge-ai -am dependency:build-classpath -Dmdep.outputFile=target/cp.deps.txt -Dmaven.repo.local=$D/m2
V=2.0.16-SNAPSHOT
J=$D/m2/forge
echo "$J/forge-core/$V/forge-core-$V.jar:$J/forge-game/$V/forge-game-$V.jar:$J/forge-ai/$V/forge-ai-$V.jar:$(cat forge-ai/target/cp.deps.txt)" > $D/cp.txt
