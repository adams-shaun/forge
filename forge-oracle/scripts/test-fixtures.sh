#!/usr/bin/env bash
# test-fixtures.sh DIR...: host replay of a fixture set (fixtures/setup,
# fixtures/cast). Each DIR holds scen.jsonl (the Items), requests.jsonl (the
# section 5.2 sidecar requests, from gorge's forge-export) and gorge.jsonl
# (gorge's own result per id, for reference). The driver's rows go to
# target/fixtures/<name>/forge.jsonl.
#
# The verdict needs gorge's comparator (oraclediff.CompareOpts), which lives
# in gorge, not here: set FORGE_ORACLE_CMP to a command that accepts
# "cmp -scen S -forge F" and prints one "<id>\tG-vs-F=<verdict>" line per item
# (gorge's P1-6 forge-diff, or the P0 scratch comparator). Without it the
# script only replays and reports harness rows. Exit 1 on any non-AGREE.
set -euo pipefail
. "$(dirname "$0")/env.sh"
[ $# -gt 0 ] || { echo "usage: test-fixtures.sh DIR..." >&2; exit 2; }
"$(dirname "$0")/javac.sh"
bad=0
for dir in "$@"; do
  dir=$(cd "$dir" && pwd)
  out=$FORGE_ORACLE_SRC/target/fixtures/$(basename "$dir")
  mkdir -p "$out"
  "$(dirname "$0")/run.sh" "$dir/requests.jsonl" "$out/forge.jsonl" 2> "$out/run.log"
  harness=$(/usr/bin/grep -c '"harness"' "$out/forge.jsonl" || true)
  echo "$(basename "$dir"): $(wc -l < "$out/forge.jsonl") rows, $harness harness"
  [ "$harness" = 0 ] || bad=1
  if [ -n "${FORGE_ORACLE_CMP:-}" ]; then
    $FORGE_ORACLE_CMP cmp -scen "$dir/scen.jsonl" -forge "$out/forge.jsonl" | tee "$out/cmp.txt"
    if /usr/bin/grep -v 'G-vs-F={"status":"AGREE"}' "$out/cmp.txt" | /usr/bin/grep -q .; then bad=1; fi
  fi
done
exit $bad
