#!/usr/bin/env bash
set -euo pipefail
D=/mnt/sata/gorge-training/forgeoracle
SRC=${FORGE_ORACLE_SRC:-$D/forge/forge-oracle}
OUT=$D/classes
rm -rf $OUT; mkdir -p $OUT
/mnt/sata/gorge-training/xmageoracle/jdk/bin/javac -J-Xmx1g --release 17 -nowarn -cp "$(cat $D/cp.txt)" -d $OUT $(find $SRC/src/main/java -name '*.java')
