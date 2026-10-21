#!/usr/bin/env bash
# WeLT with the lookup-table cost options off and on (probe, counts only): the B1 cells at which
# WeLT completes in the measurement campaign, tau_w of the campaign, one run per combination.
# Run from the repository root:  results-probe/table-cost-options-20261021/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
D=results-probe/table-cost-options-20261021
export JAVA_OPTS="${JAVA_OPTS:--Xmx8g}" PYTHONDONTWRITEBYTECODE=1
PY=$(command -v python3 || command -v python)
./build.sh > /dev/null
java -cp target/classes welt.runner.RunMetaMain start "$D/meta.json" \
    "command=$D/run.sh" "kind=probe (counts only; times are not measurements)" "limitMs=600000"
cells="citeseer:96:400,300,250,200,150,100 email_eu:336:20,15,12,10,8,6 lastfm:40:600,500,400,300,200 string_ecoli:750:2000,1900 mico:100:16000,15000 github:30:26000,25000"
for c in $cells; do
    IFS=: read -r ds tw sups <<< "$c"
    for s in ${sups//,/ }; do
        for reuse in off on; do for thr in off on; do
            WELT_TABLE_REUSE=$reuse WELT_TABLE_THRESHOLD=$thr DUMP_PATTERNS="$D/patterns/${reuse}_${thr}" BENCH_CSV=1 BENCH_ALGOS=WeLT \
                java $JAVA_OPTS -cp target/classes welt.runner.BenchmarkMain "datasets/$ds.lg" "$s" "$tw" 0 600000 0 1 > "$D/log.txt.tmp" 2>&1 || true
            cat "$D/log.txt.tmp" >> "$D/log.txt"
            grep '^CSV,' "$D/log.txt.tmp" | sed 's/^CSV,//' > "$D/row.tmp"
            if [ ! -s "$D/raw.csv" ]; then head -1 "$D/row.tmp" > "$D/raw.csv"; fi
            tail -n +2 "$D/row.tmp" >> "$D/raw.csv"
            echo "[probe] $ds $s reuse=$reuse threshold=$thr: $(tail -n +2 "$D/row.tmp" | cut -d, -f12)"
        done; done
    done
done
rm -f "$D/log.txt.tmp" "$D/row.tmp"
"$PY" "$D/summarise.py"
java -cp target/classes welt.runner.RunMetaMain finish "$D/meta.json"
