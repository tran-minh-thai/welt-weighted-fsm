#!/usr/bin/env bash
# Experiment campaign (steps below).
#
#   ./revision.sh list       describe the steps and exit
#   ./revision.sh prepare    new campaign directory, environment lock, data check, tests, topology
#   ./revision.sh b2dev      calibrate the development cells and choose the voting weights
#   ./revision.sh b1 [ds]    efficiency: GraMi, EdgeTypeBound, WEGM, WeLT over the support ranges
#                            (optional dataset name, e.g. citeseer, to split b1 into sessions)
#   ./revision.sh b2grid     sensitivity of WeLT to the voting weights on the reported datasets
#   ./revision.sh b4         one-factor ablation of WeLT
#   ./revision.sh b5         tau_w sweep (per point, and WeLT with one shared lookup table)
#   ./revision.sh b7         result-set equivalence EdgeTypeBound = WeLT over every completed cell
#
# Order: prepare, b2dev (writes voting_choice.json), then b1, b2grid, b4, b5, b7. Every step after
# prepare writes into the campaign directory named in results/v2_revision/CURRENT (or RUN_DIR).
# Peak memory is read from the b1 rows (peakMemMB).
#
# Environment: JAVA_OPTS must set -Xmx (the same value for the whole campaign, e.g. -Xmx48g);
# TIME_LIMIT_MS (default 3600000), WARMUP (1), MEASURED (5). The JDK (vendor and exact version)
# is locked by prepare; a later step on another JDK is refused.
set -euo pipefail
cd "$(dirname "$0")"
. tools/common.sh

LIMIT="${TIME_LIMIT_MS:-3600000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"
CURRENT_FILE="results/v2_revision/CURRENT"
CMD="${1:-list}"

if [ "$CMD" = "list" ] || [ "$CMD" = "-h" ] || [ "$CMD" = "--help" ]; then
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

# ---------------------------------------------------------------- environment
case " ${JAVA_OPTS:-} " in
    *" -Xmx"*) ;;
    *) echo "ERROR: set JAVA_OPTS with an explicit heap limit, e.g. JAVA_OPTS=\"-Xmx48g\"" >&2; exit 1 ;;
esac
PY=$(find_python)
if [ -z "$PY" ]; then echo "ERROR: Python 3 is required (standard library only)" >&2; exit 1; fi

jdk_identity() {
    java -XshowSettings:properties -version 2>&1 | grep -E '^ *(java\.vendor|java\.runtime\.version) =' | sed 's/^ *//' | sort
    echo "JAVA_OPTS = ${JAVA_OPTS:-}"
}

require_clean_tree
./build.sh

if [ "$CMD" = "prepare" ]; then
    HOST=$(hostname | tr -c 'A-Za-z0-9_.-\n' '_')
    RUN_DIR="${RUN_DIR:-results/v2_revision/$(date +%Y%m%d_%H%M)_${HOST}}"
    mkdir -p "$RUN_DIR" "$(dirname "$CURRENT_FILE")"
    echo "$RUN_DIR" > "$CURRENT_FILE"
    jdk_identity > "$RUN_DIR/environment.lock"
else
    RUN_DIR="${RUN_DIR:-$(cat "$CURRENT_FILE" 2>/dev/null || true)}"
    if [ -z "$RUN_DIR" ] || [ ! -d "$RUN_DIR" ]; then
        echo "ERROR: no campaign directory (run ./revision.sh prepare first, or set RUN_DIR)" >&2
        exit 1
    fi
    if ! diff <(jdk_identity) "$RUN_DIR/environment.lock" > /dev/null; then
        echo "ERROR: the JDK or JAVA_OPTS differs from the campaign lock $RUN_DIR/environment.lock:" >&2
        diff <(jdk_identity) "$RUN_DIR/environment.lock" >&2 || true
        exit 1
    fi
fi
export DUMP_PATTERNS="$RUN_DIR/patterns"
keep_awake
print_checklist
META="$RUN_DIR/meta_$(date +%Y%m%d_%H%M%S)_${CMD}.json"
java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain start "$META" \
    "command=revision.sh $*" "limitMs=$LIMIT" "warmup=$WARMUP" "measured=$MEASURED"
echo "=== campaign directory: $RUN_DIR"

# tau_w per dataset.
tau_w_of() {
    case "$1" in
        citeseer) echo 96 ;; email_eu) echo 336 ;; lastfm) echo 40 ;; mico) echo 100 ;;
        github) echo 30 ;; string_ecoli) echo 750 ;; *) echo "unknown dataset $1" >&2; exit 1 ;;
    esac
}

# B1 support ranges.
B1_CELLS=(
    "citeseer      400 300 250 200 150 100"
    "email_eu      20 15 12 10 8 6"
    "lastfm        600 500 400 300 200"
    "mico          16000 15000 14000"
    "github        26000 25000 24000"
    "string_ecoli  2000 1900 1850"
)

# The 21 points of the simplex alpha+beta+gamma=1 with step 0.2, plus the balanced voter
# (1,1,1) (voting depends only on the ratios, so it equals (1/3,1/3,1/3)).
voter_grid() {
    local a b c
    for a in 0 1 2 3 4 5; do
        for b in 0 1 2 3 4 5; do
            c=$((5 - a - b))
            [ "$c" -lt 0 ] && continue
            awk -v a="$a" -v b="$b" -v c="$c" 'BEGIN { printf "%.1f,%.1f,%.1f\n", a / 5, b / 5, c / 5 }'
        done
    done
    echo "1,1,1"
}

require_voting_choice() {
    local f="$RUN_DIR/voting_choice.json"
    if [ ! -f "$f" ]; then echo "ERROR: $f is missing; run ./revision.sh b2dev first" >&2; exit 1; fi
    WELT_VOTER=$($PY -c 'import json,sys; print(json.load(open(sys.argv[1]))["voter"])' "$f")
    WELT_WORDER=$($PY -c 'import json,sys; print(json.load(open(sys.argv[1]))["worder"])' "$f")
    export WELT_VOTER WELT_WORDER
    echo "=== WeLT voting from $f: WELT_VOTER=$WELT_VOTER WELT_WORDER=$WELT_WORDER"
}

case "$CMD" in
prepare)
    $PY tools/fetch_datasets.py
    $PY tools/selftest_tools.py
    echo "=== unit tests -> $RUN_DIR/tests.log"
    ./test.sh > "$RUN_DIR/tests.log" 2>&1 || { echo "ERROR: unit tests failed, see $RUN_DIR/tests.log" >&2; exit 1; }
    grep -E 'tests (successful|failed)' "$RUN_DIR/tests.log"
    tools/check_run_cell.sh
    $PY tools/topology.py datasets "$RUN_DIR/topology.csv"
    java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain machine results/MEASUREMENT_MACHINE.txt
    echo "=== results/MEASUREMENT_MACHINE.txt written: commit and push it with the results"
    ;;
b2dev)
    # Development cells on bitcoin_otc (not reported in the paper). tau_w = median edge weight
    # (topology.csv). minSup is scanned downwards; a value qualifies when WeLT with the
    # degree-only voter completes in [1 s, 10 min]; the scan stops at the first two
    # qualifying values or at the first run longer than 10 min. Fewer than two -> error.
    # B2DEV_DATASET, B2DEV_SUPS, B2DEV_MIN_MS, B2DEV_MAX_MS exist only to test this step quickly.
    DEVDS="${B2DEV_DATASET:-bitcoin_otc}"
    MIN_MS="${B2DEV_MIN_MS:-1000}"
    MAX_MS="${B2DEV_MAX_MS:-600000}"
    TW=$($PY -c 'import csv,sys; print([r for r in csv.DictReader(open(sys.argv[1])) if r["dataset"]==sys.argv[2]][0]["weightMedian"])' "$RUN_DIR/topology.csv" "$DEVDS.lg")
    CAL="$RUN_DIR/B2_dev_calibration.csv"
    CHOSEN=()
    for s in ${B2DEV_SUPS:-1150 1100 1050 1000 950 900 850 800 750 700}; do
        WELT_VOTER="0,1,0" WELT_WORDER=on run_cell "$CAL" "datasets/$DEVDS.lg" "$s" "$TW" WeLT "$MAX_MS" 0 1
        verdict=$($PY - "$CAL" "$s" "$MIN_MS" "$MAX_MS" <<'PY'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r["minSup"] == sys.argv[2]]
r = rows[-1]
lo, hi = float(sys.argv[3]), float(sys.argv[4])
ok = r["status"] == "OK" and r["totalMs"] != "" and lo <= float(r["totalMs"]) <= hi
print("in" if ok else ("stop" if r["status"] != "OK" else "out"))
PY
)
        echo "    calibration minSup=$s -> $verdict"
        if [ "$verdict" = "in" ]; then CHOSEN=(${CHOSEN[@]+"${CHOSEN[@]}"} "$s"); fi
        if [ "$verdict" = "stop" ] || [ "${#CHOSEN[@]}" -ge 2 ]; then break; fi
    done
    {
        echo "dataset=$DEVDS.lg tau_w=$TW (median edge weight, topology.csv)"
        echo "rule: scan minSup ${B2DEV_SUPS:-1150 1100 1050 1000 950 900 850 800 750 700}; keep values where WeLT (voter 0,1,0) completes in [$MIN_MS ms, $MAX_MS ms]; stop at two or at the first run that does not complete"
        echo "chosen minSup: ${CHOSEN[*]+${CHOSEN[*]}}"
    } > "$RUN_DIR/B2_dev_calibration.txt"
    if [ "${#CHOSEN[@]}" -lt 2 ]; then
        java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain finish "$META"
        echo "ERROR: calibration found ${#CHOSEN[@]} development cell(s); see $RUN_DIR/B2_dev_calibration.txt" >&2
        exit 1
    fi
    # The selection score is a deterministic count, so one measured run per point suffices.
    DEV="$RUN_DIR/B2_dev_grid.csv"
    for v in $(voter_grid); do
        for s in "${CHOSEN[@]}"; do
            WELT_VOTER="$v" WELT_WORDER=on run_cell "$DEV" "datasets/$DEVDS.lg" "$s" "$TW" WeLT "$LIMIT" 0 1
        done
    done
    $PY tools/choose_voting.py "$DEV" "$RUN_DIR/B2_dev_calibration.txt" "$RUN_DIR/voting_choice.json"
    ;;
b1)
    require_voting_choice
    OUT="$RUN_DIR/B1_efficiency.csv"
    for row in "${B1_CELLS[@]}"; do
        read -r ds sups <<< "$row"
        if [ -n "${2:-}" ] && [ "$ds" != "$2" ]; then continue; fi
        tw=$(tau_w_of "$ds")
        for s in $sups; do
            for algo in GraMi EdgeTypeBound WEGM WeLT; do
                run_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" "$algo" "$LIMIT" "$WARMUP" "$MEASURED"
            done
        done
    done
    ;;
b2grid)
    OUT="$RUN_DIR/B2_voting_grid.csv"
    for cell in "citeseer 250" "citeseer 200" "email_eu 12" "email_eu 10" "lastfm 600" "mico 15000" "string_ecoli 1900"; do
        read -r ds s <<< "$cell"
        tw=$(tau_w_of "$ds")
        for v in $(voter_grid); do
            WELT_VOTER="$v" WELT_WORDER=on run_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" WeLT "$LIMIT" "$WARMUP" "$MEASURED"
        done
    done
    ;;
b4)
    require_voting_choice
    OUT="$RUN_DIR/B4_ablation.csv"
    for cell in "citeseer 250" "citeseer 200" "citeseer 150" "email_eu 12" "email_eu 10" "email_eu 8" "lastfm 600" "lastfm 400"; do
        read -r ds s <<< "$cell"
        run_ablation_cell "$OUT" "datasets/$ds.lg" "$s" "$(tau_w_of "$ds")" "$LIMIT" "$WARMUP" "$MEASURED"
    done
    ;;
b5)
    require_voting_choice
    B1="$RUN_DIR/B1_efficiency.csv"
    if [ ! -f "$B1" ]; then echo "ERROR: $B1 is missing; run ./revision.sh b1 first" >&2; exit 1; fi
    OUT="$RUN_DIR/B5_tau_sweep.csv"
    SWEEP="$RUN_DIR/B5_shared_table.csv"
    for row in "${B1_CELLS[@]:0:3}"; do
        read -r ds sups <<< "$row"
        tw=$(tau_w_of "$ds")
        # minSup: the smallest value of the B1 range at which EdgeTypeBound completed; else the largest.
        s=$($PY - "$B1" "$ds.lg" "$sups" <<'PY'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r["dataset"] == sys.argv[2] and r["algorithm"] == "EdgeTypeBound"]
ok = [int(r["minSup"]) for r in rows if r["status"] == "OK"]
print(min(ok) if ok else max(int(x) for x in sys.argv[3].split()))
PY
)
        taus=$(java -cp target/classes welt.runner.SharedTableSweepMain --grid "datasets/$ds.lg" \
            0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9 "$tw" | paste -sd, -)
        echo "=== B5 $ds minSup=$s tau_w=$taus" | tee -a "$RUN_DIR/B5_choices.txt"
        for t in ${taus//,/ }; do
            for algo in EdgeTypeBound WEGM WeLT; do
                run_cell "$OUT" "datasets/$ds.lg" "$s" "$t" "$algo" "$LIMIT" "$WARMUP" "$MEASURED"
            done
        done
        tmp=$(mktemp)
        BENCH_CSV=1 java ${JAVA_OPTS:-} -cp target/classes welt.runner.SharedTableSweepMain \
            "datasets/$ds.lg" "$s" "$taus" "$LIMIT" "$WARMUP" "$MEASURED" > "$tmp" 2>&1 || true
        cat "$tmp"
        append_csv "$tmp" "$SWEEP"
        rm -f "$tmp"
    done
    ;;
b7)
    status=0
    $PY tools/compare_patterns.py "$RUN_DIR/patterns" "$RUN_DIR/B7_equivalence.csv" EdgeTypeBound WeLT || status=$?
    if [ "$status" -eq 1 ]; then echo "ERROR: EdgeTypeBound and WeLT differ on some cell (see B7_equivalence.csv)" >&2; fi
    if [ "$status" -eq 2 ]; then echo "ERROR: no cell could be compared" >&2; fi
    ;;
*)
    echo "unknown command: $CMD" >&2
    exit 1
    ;;
esac

java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain finish "$META"
echo "Done: $CMD -> $RUN_DIR"
if [ "$CMD" = "b7" ]; then exit "$status"; fi
