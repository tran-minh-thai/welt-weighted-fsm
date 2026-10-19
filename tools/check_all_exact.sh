#!/usr/bin/env bash
# Self-check of the exact ALL step (tools/heavy_subgraph.py through run_all_exact_cell). Takes
# about half a minute. Run from anywhere:  tools/check_all_exact.sh
#
# The tie graph has two edges of weight exactly 5 and one of weight 4: at tau_w = 5 and minSup 2
# the exact ALL set is {single edge}, while WeLT also keeps the 2-edge path.
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/common.sh
export CHECKPOINT=0 RESUME=1
[ -d target/classes ] || ./build.sh
PY=$(find_python)
W=target/check_all_exact
rm -rf "$W" datasets/derived/tiecheck_heavy_*; mkdir -p "$W/p" "$W/q"
printf 't # 1\nv 0 0\nv 1 0\nv 2 0\nv 3 0\ne 0 1 5.0\ne 1 2 5.0\ne 2 3 4.0\n' > "$W/tiecheck.lg"
pass=0; total=0
ok() { total=$((total + 1)); if eval "$2"; then pass=$((pass + 1)); echo "ok   $1"; else echo "FAIL $1"; fi; }
field() { "$PY" -c 'import csv,sys; r=list(csv.DictReader(open(sys.argv[1]))); print(r[int(sys.argv[2])-1][sys.argv[3]])' "$@" | tr -d '\r'; }
rows() { "$PY" -c 'import csv,sys; print(sum(1 for _ in csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="\t")))' "$1" | tr -d '\r'; }

H=datasets/derived/tiecheck_heavy_5.lg
"$PY" tools/heavy_subgraph.py "$W/tiecheck.lg" 5 "$H" > /dev/null
ok "heavy subgraph keeps the two edges of weight 5" '[ "$(grep -c "^e " "$H")" -eq 2 ] && grep -q "^e 1 2 5.0$" "$H" && ! grep -q " 4.0$" "$H"'
ok "every vertex is kept" '[ "$(grep -c "^v " "$H")" -eq 4 ]'
ok "an unchanged dataset reuses the file" '"$PY" tools/heavy_subgraph.py "$W/tiecheck.lg" 5 "$H" | grep -q "is current"'
printf 't # 1\nv 0 0\nv 1 0\nv 2 0\nv 3 0\ne 0 1 5.0\ne 1 2 5.0\ne 2 3 6.0\n' > "$W/tiecheck.lg"
"$PY" tools/heavy_subgraph.py "$W/tiecheck.lg" 5 "$H" > /dev/null
ok "a changed dataset is exported again" '[ "$(grep -c "^e " "$H")" -eq 3 ]'
printf 't # 1\nv 0 0\nv 1 0\nv 2 0\nv 3 0\ne 0 1 5.0\ne 1 2 5.0\ne 2 3 4.0\n' > "$W/tiecheck.lg"

run_all_exact_cell "$W/all.csv" "$W/tiecheck.lg" 2 5 600000 1 1 "$W/p" > "$W/log1.txt" 2>&1
F="$W/p/tiecheck_heavy_5_GraMi_2_5.0.tsv"
ok "exact ALL cell: status OK and one pattern" '[ "$(field "$W/all.csv" 1 status)" = OK ] && [ "$(rows "$F")" -eq 1 ]'
run_all_exact_cell "$W/all.csv" "$W/tiecheck.lg" 2 5 600000 1 1 "$W/p" > "$W/log2.txt" 2>&1
ok "the same cell again is skipped" '[ "$(($(wc -l < "$W/all.csv")))" -eq 2 ] && grep -q "skip" "$W/log2.txt"'
cp "$F" "$W/q/tiecheck_ALL-exact_2_5.0.tsv"
java -cp target/classes welt.runner.ResumMain definition "$W/tiecheck.lg" 2 5 "$W/q" > /dev/null
DUMP_PATTERNS="$W/q" BENCH_ALGOS=WeLT java -cp target/classes welt.runner.BenchmarkMain "$W/tiecheck.lg" 2 5 0 600000 0 1 > /dev/null 2>&1
ok "exact ALL equals the ALL definition" '"$PY" tools/compare_patterns.py "$W/q" "$W/c1.csv" ALL-exact ALL-definition > /dev/null'
ok "exact ALL is contained in WeLT" '"$PY" tools/compare_patterns.py --subset "$W/q" "$W/c2.csv" ALL-exact WeLT > /dev/null'
ok "and is a strict subset here" '! "$PY" tools/compare_patterns.py "$W/q" "$W/c3.csv" ALL-exact WeLT > /dev/null'

run_all_exact_cell "$W/all.csv" datasets/citeseer.lg 300 80 600000 1 1 "$W/p" > "$W/log3.txt" 2>&1
mkdir -p "$W/r"
cp "$W/p/citeseer_heavy_80_GraMi_300_80.0.tsv" "$W/r/citeseer_ALL-exact_300_80.0.tsv"
java -cp target/classes welt.runner.ResumMain definition datasets/citeseer.lg 300 80 "$W/r" > /dev/null
ok "real data (citeseer 300, tau_w 80): exact ALL equals the definition, non-empty" \
   '"$PY" tools/compare_patterns.py "$W/r" "$W/c4.csv" ALL-exact ALL-definition > /dev/null && [ "$(rows "$W/r/citeseer_ALL-exact_300_80.0.tsv")" -gt 0 ]'

ok "real data: kept weights are written exactly (all >= 80, the fractional ones kept)" \
   '"$PY" -c "import sys; w=[float(l.split()[3]) for l in open(sys.argv[1]) if l.startswith(\"e \")]; sys.exit(0 if w and min(w) >= 80 and any(x != int(x) for x in w) else 1)" datasets/derived/citeseer_heavy_80.lg'

if [ "$pass" -eq "$total" ]; then
    echo "check_all_exact: OK ($pass/$total cases)"
    rm -rf "$W" datasets/derived/tiecheck_heavy_*
else
    echo "check_all_exact: FAILED ($pass/$total cases; logs in $W)" >&2
    exit 1
fi
