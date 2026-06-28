#!/usr/bin/env bash
# Re-run ONLY the timed-out (T.O.) cells from the previous SC1/SC2 results at a longer per-run
# limit (default 60 min), so a borderline case may finish. The script auto-detects the T.O.
# cells, re-runs just the timed-out algorithm(s) of each configuration (a single run is enough
# to learn "finishes or not"), and writes results/RERUN_<datetime>_to.csv.
#
# Merging: when rebuilding the tables, a new FINITE row supersedes the old T.O. row for the same
# (dataset, algorithm, minSup); a row that times out again stays T.O. The previously FINISHED
# cells need no re-run (they finished well under the new limit), so the merged result is a
# consistent "60-minute limit" experiment.
#
# Usage:
#   ./rerun_to.sh                 # re-run every T.O. cell
#   ./rerun_to.sh string          # only configs whose line matches "string" (scope it down)
#   RERUN_LIMIT_MS=1800000 ./rerun_to.sh   # use a 30-min limit instead of 60
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/classes ] || ./build.sh
LIMIT="${RERUN_LIMIT_MS:-3600000}"
FILTER="${1:-}"

# Keep the machine awake for the whole (possibly multi-hour) run.
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ &
fi

# Source result files (skip any that do not exist).
SRC=()
for f in results/SC1_*efficiency.csv results/SC2_*scalability.csv; do [ -f "$f" ] && SRC+=("$f"); done
if [ ${#SRC[@]} -eq 0 ]; then echo "No SC1/SC2 result CSV found in results/." >&2; exit 1; fi

stamp=$(date +%Y%m%d_%H%M%S)
out="results/RERUN_${stamp}_to.csv"
echo "dataset,algorithm,weightModel,minSup,minWeight,budget,limitMs,freq,candMNI,mineMNI,medianMs,timedOut,peakMemMB" > "$out"

# Build a list of (dataset|minSup|minWeight|budget) -> comma-list of TIMED-OUT algorithms.
# CSV column 12 is timedOut ("-" when the cell finished, "k/n" or "1/1" when it timed out).
cfgfile=$(mktemp)
awk -F, 'FNR>1 && $12!="-" && $12!="" {k=$1"|"$4"|"$5"|"$6; a[k]=a[k]","$2}
         END{for(k in a) print k" "substr(a[k],2)}' "${SRC[@]}" | sort > "$cfgfile"
if [ -n "$FILTER" ]; then grep "$FILTER" "$cfgfile" > "$cfgfile.f" || true; mv "$cfgfile.f" "$cfgfile"; fi

# Continue mode: skip TO configs already re-run in a previous RERUN_*.csv (matched by dataset|minSup).
prevcsv=$(ls results/RERUN_*.csv 2>/dev/null || true)
if [ -n "$prevcsv" ]; then
    awk -F, 'FNR>1{print $1"|"$4}' $prevcsv | sort -u > "$cfgfile.done"
    awk -F'|' 'NR==FNR{d[$1"|"$2]=1;next} !(($1"|"$2) in d)' "$cfgfile.done" "$cfgfile" > "$cfgfile.keep"
    mv "$cfgfile.keep" "$cfgfile"; rm -f "$cfgfile.done"
fi

n=$(grep -c . "$cfgfile" || true)
if [ "$n" -eq 0 ]; then echo "No timed-out cells found (filter='${FILTER}')."; rm -f "$cfgfile"; exit 0; fi
echo "=== $n timed-out configuration(s) to re-run at $((LIMIT/60000)) min/run -> $out ==="
sed 's/^/  /' "$cfgfile"

while read -r cfg algos; do
    IFS='|' read -r ds minSup minWeight budget <<< "$cfg"
    # Run WeLT FIRST, then the timed-out baselines, so WeLT's result lands before the long T.O.s.
    base=$(echo "$algos" | tr ',' '\n' | grep -vx 'WeLT' | paste -sd, -)
    runalgos="WeLT${base:+,$base}"
    echo ">>> datasets/$ds  minSup=$minSup  tau_w=$minWeight  algos=$runalgos  limit=${LIMIT}ms"
    tmp=$(mktemp)
    BENCH_CSV=1 BENCH_ALGOS="$runalgos" java ${JAVA_OPTS:-} -cp target/classes \
        welt.runner.BenchmarkMain "datasets/$ds" "$minSup" "$minWeight" "$budget" "$LIMIT" 0 1 > "$tmp" 2>&1 || true
    cat "$tmp"
    grep '^CSV,' "$tmp" | grep -v 'CSV,dataset' | sed 's/^CSV,//' >> "$out" || true
    rm -f "$tmp"
done < "$cfgfile"
rm -f "$cfgfile"
echo "--- done -> $out (new finite rows supersede the old T.O. rows when building tables) ---"
