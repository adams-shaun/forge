#!/usr/bin/env bash
# Card-database-free unit tests of the driver, inside the test budget
# (2 GB, 2 CPU, 60 s wall for the whole script). javac main + test against
# $FORGE_ORACLE_DIR/cp.txt plus TestNG, then TestNG with -Xmx1536m. Output
# stays under forge-oracle/target (a jailed seat can write only its worktree).
#   systemd-run --user --scope -q -p MemoryMax=2G -p CPUQuota=200% forge-oracle/run-unit-tests.sh
set -euo pipefail
if [ -z "${FORGE_ORACLE_UNDER_TIMEOUT:-}" ]; then
  export FORGE_ORACLE_UNDER_TIMEOUT=1
  exec timeout 60 "$0" "$@"
fi
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/scripts/env.sh"
M2=$FORGE_ORACLE_DIR/m2
TESTNG=$M2/org/testng/testng/7.10.2/testng-7.10.2.jar:$M2/com/beust/jcommander/1.82/jcommander-1.82.jar
OUT=$HERE/target/unit
CP="$(cat "$FORGE_ORACLE_DIR/cp.txt"):$TESTNG"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/tmp"
"$JAVAC" -J-Xmx768m --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
  $(find "$HERE/src/main/java" "$HERE/src/test/java" -name '*.java' | sort)
TESTS=$(cd "$HERE/src/test/java" && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort | paste -sd, -)
exec "$JAVA" -Xmx1536m -XX:+UseSerialGC -Djava.awt.headless=true -Dtinylog.level=warn -Djava.io.tmpdir="$OUT/tmp" \
  -Dforge.oracle.res="${FORGE_RES:-$HERE/../forge-gui/res}" \
  -cp "$OUT/classes:$CP" org.testng.TestNG -usedefaultlisteners false -d "$OUT/testng" -testclass "$TESTS" "$@"
