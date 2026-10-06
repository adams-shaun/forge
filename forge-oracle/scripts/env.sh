# Sourced by the forge-oracle scripts: host paths, overridable from the environment.
# FORGE_ORACLE_DIR holds the local Maven repo (m2/), cp.txt and lib/ (rebuildable, off-repo).
FORGE_ORACLE_DIR=${FORGE_ORACLE_DIR:-/mnt/sata/gorge-training/forgeoracle}
FORGE_ORACLE_JDK=${FORGE_ORACLE_JDK:-/mnt/sata/gorge-training/xmageoracle/jdk}
FORGE_ORACLE_MVN=${FORGE_ORACLE_MVN:-/mnt/sata/gorge-training/xmageoracle/maven/bin/mvn}
FORGE_ORACLE_SRC=${FORGE_ORACLE_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
JAVA=$FORGE_ORACLE_JDK/bin/java
JAVAC=$FORGE_ORACLE_JDK/bin/javac
