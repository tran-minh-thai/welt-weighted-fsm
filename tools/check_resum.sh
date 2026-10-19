#!/usr/bin/env bash
# Self-check of the ReSuM step (tools/run_resum.py through run_resum_cell). Needs the ReSuM build
# (python tools/build_resum.py). Takes about half a minute. Run from anywhere:
#   tools/check_resum.sh
#
# The tie graph has two edges of weight exactly 5 and one of weight 4, so at tau_w = 5 the
# relevance threshold decides the answer: ALL(minSup 2) = {single edge}, while WeLT also keeps
# the 2-edge path (MNI 2 in G, one appearance with both edges of weight 5).
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/common.sh
export CHECKPOINT=0 RESUME=1
[ -d target/classes ] || ./build.sh
PY=$(find_python)
W=target/check_resum
rm -rf "$W"; mkdir -p "$W/patterns"
printf 't # 1\nv 0 0\nv 1 0\nv 2 0\nv 3 0\ne 0 1 5.0\ne 1 2 5.0\ne 2 3 4.0\n' > "$W/tiecheck.lg"
OUT="$W/resum.csv"
export DUMP_PATTERNS="$W/patterns"
pass=0; total=0
ok() { total=$((total + 1)); if eval "$2"; then pass=$((pass + 1)); echo "ok   $1"; else echo "FAIL $1"; fi; }
field() { # <csv> <row number from 1> <column>
    "$PY" -c 'import csv,sys; r=list(csv.DictReader(open(sys.argv[1]))); print(r[int(sys.argv[2])-1][sys.argv[3]])' "$@" | tr -d '\r'
}

run_resum_cell "$OUT" "$W/tiecheck.lg" 2 5 600000 1 1 > "$W/log1.txt" 2>&1
ok "completed cell: status OK, one pattern" '[ "$(field "$OUT" 1 status)" = OK ] && [ "$(field "$OUT" 1 R)" = 1 ]'
run_resum_cell "$OUT" "$W/tiecheck.lg" 2 5 600000 1 1 > "$W/log2.txt" 2>&1
ok "the same cell again is skipped" '[ "$(($(wc -l < "$OUT")))" -eq 2 ] && grep -q "skip" "$W/log2.txt"'
java -cp target/classes welt.runner.ResumMain definition "$W/tiecheck.lg" 2 5 "$W/patterns" > /dev/null
DUMP_PATTERNS="$W/patterns" BENCH_ALGOS=WeLT java -cp target/classes welt.runner.BenchmarkMain "$W/tiecheck.lg" 2 5 0 600000 0 1 > /dev/null 2>&1
ok "ReSuM-ALL equals the ALL definition" '"$PY" tools/compare_patterns.py "$W/patterns" "$W/c1.csv" ReSuM-ALL ALL-definition > /dev/null'
ok "ReSuM-ALL is contained in WeLT" '"$PY" tools/compare_patterns.py --subset "$W/patterns" "$W/c2.csv" ReSuM-ALL WeLT > /dev/null'
ok "and is a strict subset here (the check has something to see)" '! "$PY" tools/compare_patterns.py "$W/patterns" "$W/c3.csv" ReSuM-ALL WeLT > /dev/null'
run_resum_cell "$OUT" datasets/citeseer.lg 300 50 1 1 1 > "$W/log3.txt" 2>&1
ok "a run beyond the limit is TO" '[ "$(field "$OUT" 2 status)" = TO ]'
JAVA_OPTS="-Xmx1k" run_resum_cell "$OUT" "$W/tiecheck.lg" 2 4 600000 1 1 > "$W/log4.txt" 2>&1
ok "a JVM that cannot start is CRASH" '[ "$(field "$OUT" 3 status)" = CRASH ]'
ok "every row has every column" '"$PY" -c "import csv,sys; r=list(csv.reader(open(sys.argv[1]))); sys.exit(0 if len(r)==4 and len({len(x) for x in r})==1 else 1)" "$OUT"'

if [ "$pass" -eq "$total" ]; then
    echo "check_resum: OK ($pass/$total cases)"
    rm -rf "$W"
else
    echo "check_resum: FAILED ($pass/$total cases; logs in $W)" >&2
    exit 1
fi
