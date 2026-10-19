#!/usr/bin/env bash
# Self-check of tools/common.sh run_cell: forces the four outcomes of a cell and verifies the
# CSV rows (status, partial flag, aligned columns). Takes about a minute. Run from anywhere:
#   tools/check_run_cell.sh
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/common.sh
export RESUME=0 CHECKPOINT=0   # the same cell is run twice on purpose; nothing is pushed
[ -d target/classes ] || ./build.sh
OUT=$(mktemp)
: > "$OUT"
run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 1 2 > /dev/null                                  # completes
JAVA_OPTS="-Xmx16m" run_cell "$OUT" datasets/email_eu.lg 6 336 GraMi 180000 1 1 > /dev/null         # OOM caught in the JVM
JAVA_OPTS="-Xmx16m -XX:+ExitOnOutOfMemoryError" run_cell "$OUT" datasets/email_eu.lg 6 336 GraMi 180000 1 1 > /dev/null  # JVM killed by OOM
JAVA_OPTS="-Xmx1k" run_cell "$OUT" datasets/citeseer.lg 300 96 WeLT 0 1 1 > /dev/null                # JVM cannot start
expected="OK,0 OOM,1 OOM,1 CRASH,1"
got=$(awk -F, 'NR==1 {for (i=1;i<=NF;i++) c[$i]=i; n=NF; next}
               NF!=n {print "MISALIGNED"; exit}
               {printf "%s%s,%s", (NR>2?" ":""), $c["status"], $c["partial"]}' "$OUT")
rm -f "$OUT"
if [ "$got" = "$expected" ]; then
    echo "check_run_cell: OK (4/4 outcomes: $got)"
else
    echo "check_run_cell: FAILED: expected '$expected', got '$got'" >&2
    exit 1
fi
