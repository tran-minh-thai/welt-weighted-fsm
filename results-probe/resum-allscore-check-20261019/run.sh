#!/usr/bin/env bash
# ReSuM's ALL score against its definition and against WeLT, on small cells (probe, counts only).
# Run from the repository root:  results-probe/resum-allscore-check-20261019/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
D=results-probe/resum-allscore-check-20261019
export JAVA_OPTS="${JAVA_OPTS:--Xmx8g}" PYTHONDONTWRITEBYTECODE=1
PY=$(command -v python3 || command -v python)
./build.sh > /dev/null
"$PY" tools/build_resum.py > /dev/null
java -cp target/classes welt.runner.RunMetaMain start "$D/meta.json" \
    "command=$D/run.sh" "kind=probe (counts only; times are not measurements)" "limitMs=600000"
cells="citeseer:300:50 citeseer:300:80 citeseer:300:96 citeseer:200:80 email_eu:20:100 email_eu:20:336 email_eu:15:100 email_eu:15:336 email_eu:10:100 email_eu:10:336 lastfm:600:40"
for c in $cells; do
    IFS=: read -r ds s tw <<< "$c"
    "$PY" tools/run_resum.py "$D/resum.csv" "datasets/$ds.lg" "$s" "$tw" 600000 1 1 "$D/patterns"
    if [ -e "$D/patterns/${ds}_ReSuM-ALL_${s}_${tw}.0.tsv" ]; then
        java $JAVA_OPTS -cp target/classes welt.runner.ResumMain definition "datasets/$ds.lg" "$s" "$tw" "$D/patterns"
    fi
    DUMP_PATTERNS="$D/patterns" BENCH_CSV=1 BENCH_ALGOS=WeLT java $JAVA_OPTS -cp target/classes welt.runner.BenchmarkMain \
        "datasets/$ds.lg" "$s" "$tw" 0 600000 0 1 > "$D/welt_${ds}_${s}_${tw}.log" 2>&1
done
"$PY" tools/compare_patterns.py "$D/patterns" "$D/resum_vs_definition.csv" ReSuM-ALL ALL-definition || true
"$PY" tools/compare_patterns.py --subset "$D/patterns" "$D/resum_within_welt.csv" ReSuM-ALL WeLT || true
java -cp target/classes welt.runner.RunMetaMain finish "$D/meta.json"
