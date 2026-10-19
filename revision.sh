#!/usr/bin/env bash
# Experiment campaign (steps below).
#
#   ./revision.sh all        the whole campaign, resumable: run it again to continue
#   ./revision.sh list       describe the steps and exit
#   ./revision.sh prepare    new campaign directory, environment lock, data check, tests, topology
#   ./revision.sh voting     record the voting weights: (1,1,1), fixed in advance
#   ./revision.sh b2dev      optional: calibrate development cells and choose the weights from data
#   ./revision.sh b1 [ds]    efficiency: GraMi, GraMiFilter, WEGM, WeLT over the support
#                            ranges (optional dataset name, e.g. citeseer, to split b1 into sessions)
#   ./revision.sh allexact [ds]  the ALL score of ReSuM computed exactly (GraMi on the edges of
#                            weight >= tau_w) on the b1 cells
#   ./revision.sh resum [ds] ReSuM's released code on the b1 cells (not part of all)
#   ./revision.sh b2grid     sensitivity of WeLT to the voting weights on the reported datasets
#   ./revision.sh b4         one-factor ablation of WeLT
#   ./revision.sh b2hard     voting sensitivity at the hardest cell of each dataset (after b1)
#   ./revision.sh b4hard     ablation at the hardest cell of citeseer, email_eu, lastfm (after b1)
#   ./revision.sh b1low      WeLT below the b1 ranges (email_eu 4, 3; lastfm 100, 50)
#   ./revision.sh b4low      ablation of the lookup table, the vote and the table cost options at those cells
#   ./revision.sh b4rep      three JVM repetitions of the low-support ablation where the table claim rests
#                            (email_eu 4, 3: full, noTableBuild, noTable, noP2; lastfm 100: every b4low variant)
#   ./revision.sh b3live     peak live heap, one untimed run per cell with forced collections (email_eu 12,
#                            10: every algorithm; email_eu 4, 3 and lastfm 100, 50: WeLT)
#   ./revision.sh b3heap     smallest heap at which a cell completes (not part of all)
#   ./revision.sh wegmrange  WEGM, the exact ALL score and WeLT at the supports where WEGM returns patterns
#   ./revision.sh b5 [ds]    tau_w sweep: comparison at a support where GraMi completes, a WeLT
#                            shared-table sweep, and a WeLT-only sweep at the lowest WeLT support
#   ./revision.sh downstream node classification with pattern features (tools/downstream; not part of all;
#                            DOWNSTREAM_PYTHON is the interpreter with tools/downstream/requirements.txt)
#   ./revision.sh b7         result sets over every completed cell: GraMiFilter = WeLT,
#                            exact ALL = its definition and within WeLT; WEGM within ALL within WeLT
#
# Order: prepare, voting (writes voting_choice.json), then b2grid, b4, b1 and allexact, b5, b7. Every step after
# prepare writes into the campaign directory named in results/v2_revision/CURRENT (or RUN_DIR).
# Memory: the b1 rows count the retained data from its definition (visitedCodeChars, memoCodeChars,
# frontierDomainIntsPeak, tableKeptPatterns, tableDomainInts); b3live measures the peak live heap and
# b3heap the smallest heap that completes a cell. peakMemMB (heap pools since the run started, garbage
# included) is kept for reference.
#
# Environment: JAVA_OPTS must set -Xmx (the same value for the whole campaign, e.g. -Xmx48g);
# TIME_LIMIT_MS (default 3600000), WARMUP (1), MEASURED (5). The JDK (vendor and exact version)
# is locked by prepare; a later step on another JDK is refused.
#
# Resume: every step skips the cells that already have a row, so an interrupted step is
# continued by running the same command again (prepare reuses the current campaign unless
# NEW_CAMPAIGN=1). CHECKPOINT=1 commits results/ after every cell, pushes it to the branch
# CAMPAIGN_BRANCH (default campaign-revision-r2) and writes one line to the status repository
# (RUN_STATUS_DIR, default $HOME/work/run-status; STATUS_PROJECT).
set -euo pipefail
cd "$(dirname "$0")"
. tools/common.sh

LIMIT="${TIME_LIMIT_MS:-3600000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"
CURRENT_FILE="results/v2_revision/CURRENT"
CMD="${1:-list}"

if [ "$CMD" = "list" ] || [ "$CMD" = "-h" ] || [ "$CMD" = "--help" ]; then
    sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

# ---------------------------------------------------------------- whole campaign
# all: prepare (skipped once prepare.done exists), voting (skipped once voting_choice.json
# exists), b2grid, b4, b1 and allexact per dataset, b2hard, b4hard, b1low, b4low, b4rep, b3live, wegmrange, b5 per dataset, b7. Every step skips its finished
# cells. A failing step stops the sequence and reports "all STOPPED at <step>".
# ALL_DRY=1 prints the steps that would run; ALL_STEPS="b4 b1:citeseer" runs a subset.
if [ "$CMD" = "all" ]; then
    all_step() {
        local code=0
        echo "=== all: $*"
        if [ "${ALL_DRY:-0}" = "1" ]; then return 0; fi
        bash "$0" "$@" || code=$?
        if [ "$code" -ne 0 ]; then
            report_status "all STOPPED at $* (exit $code)"
            exit "$code"
        fi
    }
    if [ -n "${ALL_STEPS:-}" ]; then
        for st in $ALL_STEPS; do all_step ${st/:/ }; done
        exit 0
    fi
    cur="$(cat "$CURRENT_FILE" 2>/dev/null || true)"
    if [ -z "$cur" ] || [ ! -f "$cur/prepare.done" ]; then all_step prepare; fi
    cur="$(cat "$CURRENT_FILE" 2>/dev/null || true)"
    if [ -z "$cur" ] || [ ! -f "$cur/voting_choice.json" ]; then all_step voting; fi
    all_step b2grid
    all_step b4
    for ds in citeseer email_eu string_ecoli lastfm mico github; do all_step b1 "$ds"; all_step allexact "$ds"; done
    all_step b2hard
    all_step b4hard
    all_step b1low
    all_step b4low
    all_step b4rep
    all_step b3live
    all_step wegmrange
    for ds in citeseer email_eu lastfm; do all_step b5 "$ds"; done
    all_step b7
    [ "${ALL_DRY:-0}" = "1" ] || report_status "all done"
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
    java -XshowSettings:properties -version 2>&1 | tr -d '\r' | grep -E '^ *(java\.vendor|java\.runtime\.version) =' | sed 's/^ *//' | sort
    echo "JAVA_OPTS = ${JAVA_OPTS:-}"
}

require_clean_tree
./build.sh

EXISTING="$(cat "$CURRENT_FILE" 2>/dev/null || true)"
if [ "$CMD" = "prepare" ] && { [ "${NEW_CAMPAIGN:-0}" = "1" ] || [ -z "$EXISTING" ] || [ ! -d "$EXISTING" ]; }; then
    HOST=$(hostname | sed 's/[^A-Za-z0-9_.-]/_/g')
    RUN_DIR="${RUN_DIR:-results/v2_revision/$(date +%Y%m%d_%H%M)_${HOST}}"
    mkdir -p "$RUN_DIR" "$(dirname "$CURRENT_FILE")"
    echo "$RUN_DIR" > "$CURRENT_FILE"
    jdk_identity > "$RUN_DIR/environment.lock"
else
    RUN_DIR="${RUN_DIR:-$EXISTING}"
    if [ -z "$RUN_DIR" ] || [ ! -d "$RUN_DIR" ]; then
        echo "ERROR: no campaign directory (run ./revision.sh prepare first, or set RUN_DIR)" >&2
        exit 1
    fi
    if ! diff <(jdk_identity) <(tr -d '\r' < "$RUN_DIR/environment.lock") > /dev/null; then
        echo "ERROR: the JDK or JAVA_OPTS differs from the campaign lock $RUN_DIR/environment.lock:" >&2
        diff <(jdk_identity) <(tr -d '\r' < "$RUN_DIR/environment.lock") >&2 || true
        exit 1
    fi
fi
# The measurement code is fixed for the whole campaign: the git tree of src/ is recorded at the
# first step and every later step must run the same tree (the scripts may change, src/ may not).
SRC_TREE=$(git rev-parse HEAD:src)
if [ -f "$RUN_DIR/src.lock" ]; then
    if [ "$(tr -d '\r' < "$RUN_DIR/src.lock")" != "$SRC_TREE" ]; then
        echo "ERROR: src/ differs from the campaign lock $RUN_DIR/src.lock ($(tr -d '\r' < "$RUN_DIR/src.lock") vs $SRC_TREE)" >&2
        exit 1
    fi
else
    echo "$SRC_TREE" > "$RUN_DIR/src.lock"
fi
export DUMP_PATTERNS="$RUN_DIR/patterns"
keep_awake
print_checklist
META="$RUN_DIR/meta_$(date +%Y%m%d_%H%M%S)_${CMD}.json"
java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain start "$META" \
    "command=revision.sh $*" "limitMs=$LIMIT" "warmup=$WARMUP" "measured=$MEASURED"
echo "=== campaign directory: $RUN_DIR"
report_status "$CMD ${2:-} start (code $(git log -1 --format=%h -- . ':(exclude)results'), $RUN_DIR)"

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
    WELT_VOTER=$($PY -c 'import json,sys; print(json.load(open(sys.argv[1]))["voter"])' "$f" | tr -d '\r')
    WELT_WORDER=$($PY -c 'import json,sys; print(json.load(open(sys.argv[1]))["worder"])' "$f" | tr -d '\r')
    export WELT_VOTER WELT_WORDER
    echo "=== WeLT voting from $f: WELT_VOTER=$WELT_VOTER WELT_WORDER=$WELT_WORDER"
}

case "$CMD" in
prepare)
    $PY tools/fetch_datasets.py
    $PY tools/build_resum.py
    cp third_party/resum/build_info.json "$RUN_DIR/resum_build_info.json"
    $PY tools/selftest_tools.py
    echo "=== unit tests -> $RUN_DIR/tests.log"
    ./test.sh > "$RUN_DIR/tests.log" 2>&1 || { echo "ERROR: unit tests failed, see $RUN_DIR/tests.log" >&2; exit 1; }
    grep -E 'tests (successful|failed)' "$RUN_DIR/tests.log"
    tools/check_run_cell.sh
    tools/check_resume.sh
    tools/check_resum.sh
    tools/check_all_exact.sh
    $PY tools/topology.py datasets "$RUN_DIR/topology.csv"
    java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain machine results/MEASUREMENT_MACHINE.txt
    touch "$RUN_DIR/prepare.done"
    ;;
voting)
    # The voting weights are fixed in advance: (1,1,1), the WeLT default, with the weight-aware
    # order. b2grid measures the sensitivity to them; no weight is chosen from data.
    VC="$RUN_DIR/voting_choice.json"
    if [ ! -f "$VC" ]; then
        printf '{\n  "voter": "1,1,1",\n  "worder": "on",\n  "source": "fixed in advance (the WeLT default); b2grid is a sensitivity analysis, no weight is chosen from data"\n}\n' > "$VC"
    fi
    cat "$VC"
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
    TW=$($PY -c 'import csv,sys; print([r for r in csv.DictReader(open(sys.argv[1])) if r["dataset"]==sys.argv[2]][0]["weightMedian"])' "$RUN_DIR/topology.csv" "$DEVDS.lg" | tr -d '\r')
    CAL="$RUN_DIR/B2_dev_calibration.csv"
    CHOSEN=()
    for s in ${B2DEV_SUPS:-1150 1100 1050 1000 950 900 850 800 750 700}; do
        WELT_VOTER="0,1,0" WELT_WORDER=on run_cell "$CAL" "datasets/$DEVDS.lg" "$s" "$TW" WeLT "$MAX_MS" 0 1
        verdict=$($PY - "$CAL" "$s" "$MIN_MS" "$MAX_MS" <<'PY' | tr -d '\r'
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
        checkpoint "b2dev FAILED: fewer than two development cells"
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
            for algo in GraMi GraMiFilter WEGM WeLT; do
                run_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" "$algo" "$LIMIT" "$WARMUP" "$MEASURED"
            done
        done
    done
    ;;
allexact)
    # ReSuM's ALL score computed exactly on the b1 cells: GraMi on G restricted to weight >= tau_w.
    OUT="$RUN_DIR/B1_all_exact.csv"
    for row in "${B1_CELLS[@]}"; do
        read -r ds sups <<< "$row"
        if [ -n "${2:-}" ] && [ "$ds" != "$2" ]; then continue; fi
        tw=$(tau_w_of "$ds")
        for s in $sups; do
            run_all_exact_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" "$LIMIT" "$WARMUP" "$MEASURED" "$RUN_DIR/patterns_all_exact"
        done
    done
    ;;
resum)
    # ReSuM's released code on the b1 cells, relevance threshold = the largest double below tau_w.
    OUT="$RUN_DIR/B1_resum.csv"
    for row in "${B1_CELLS[@]}"; do
        read -r ds sups <<< "$row"
        if [ -n "${2:-}" ] && [ "$ds" != "$2" ]; then continue; fi
        tw=$(tau_w_of "$ds")
        for s in $sups; do
            run_resum_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" "$LIMIT" "$WARMUP" "$MEASURED"
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
b1low|b4low)
    # Cells below the b1 ranges, fixed before any run of this campaign from the lower-support
    # count probe (results-probe/l*-support-counts-20261019): at email_eu 4 and 3 and lastfm 100
    # and 50 WeLT completed within 10 minutes on the development machine, in about 1 s to 2.5 min
    # per run there, where the b1 cells take milliseconds. The other algorithms are not run there:
    # GraMi and GraMiFilter already exceed the limit at higher supports of these datasets, and WEGM
    # returns no pattern there (results-invariant/wegm-single-edge-support-20261021). b4low runs
    # the variants that bear on the lookup table (noTableBuild: no table at all), the vote and the
    # table cost options; noWext, noBnb, noP3 and p3R are left out (noWext and noBnb already
    # exceed the limit in b4 at higher supports).
    require_voting_choice
    LOW_CELLS=("email_eu 4" "email_eu 3" "lastfm 100" "lastfm 50")
    for cell in "${LOW_CELLS[@]}"; do
        read -r ds s <<< "$cell"
        tw=$(tau_w_of "$ds")
        if [ "$CMD" = "b1low" ]; then
            run_cell "$RUN_DIR/B1_low.csv" "datasets/$ds.lg" "$s" "$tw" WeLT "$LIMIT" "$WARMUP" "$MEASURED"
        else
            ABLATION_ONLY="full noP1 noP2 noP2single noTable noTableBuild voteDeg noWorder noTableReuse noTableThreshold noTableOptions" \
                run_ablation_cell "$RUN_DIR/B4_low.csv" "datasets/$ds.lg" "$s" "$tw" "$LIMIT" "$WARMUP" "$MEASURED"
        fi
    done
    ;;
b4rep)
    # Variation between JVMs, which the within-JVM sd of the tie rule does not include: each cell
    # is run in three separate passes (B4_rep_jvm1..3.csv, one JVM per variant and pass, the usual
    # warm-up and measured runs), the passes one after the other so that a slow period of the
    # machine falls on every variant of a pass, and the variant order rotated between passes. Cells:
    # email_eu 4 and 3, where the only time difference of the lookup table rests (full, noTableBuild,
    # noTable, noP2), and lastfm 100, whose b4low full run was slower than every variant with the
    # same search counters (every b4low variant). Reading rule, fixed before the run
    # (tools/summarise_campaign.py, rep_verdict): a variant is slower (faster) than full only if it
    # is slower (faster) in all three passes and its smallest (largest) ratio to full lies beyond the
    # largest of the spread of full over the passes, the spread of the variant, and 1.10.
    require_voting_choice
    REP_EMAIL=(full noTableBuild noTable noP2)
    REP_LASTFM=(full noP1 noP2 noP2single noTable noTableBuild voteDeg noWorder noTableReuse noTableThreshold noTableOptions)
    rotate() { local k=$1; shift; local a=("$@"); local n=${#a[@]}; local i; for ((i = 0; i < n; i++)); do printf '%s ' "${a[$(((i + k) % n))]}"; done; }
    for pass in 1 2 3; do
        for cell in "email_eu 4" "email_eu 3" "lastfm 100"; do
            read -r ds s <<< "$cell"
            if [ "$ds" = email_eu ]; then order=$(rotate $((pass - 1)) "${REP_EMAIL[@]}"); else order=$(rotate $((pass - 1)) "${REP_LASTFM[@]}"); fi
            ABLATION_ONLY="$order" run_ablation_cell "$RUN_DIR/B4_rep_jvm$pass.csv" "datasets/$ds.lg" "$s" "$(tau_w_of "$ds")" "$LIMIT" "$WARMUP" "$MEASURED"
        done
    done
    ;;
b3live)
    # Peak live heap: one run per cell with WELT_LIVE_HEAP=on (no warm-up, one run, not timed):
    # a full collection is forced at the start and the end of mining and whenever the retained data
    # exceeds 1.05 times its value at the previous collection; the heap used right after each
    # collection is the live heap there. Cells: email_eu 12 and 10, the b1 cells where GraMi and
    # GraMiFilter also complete in a non-trivial time (every algorithm), and the b1low cells (WeLT).
    require_voting_choice
    LIVE_OUT="$RUN_DIR/B3_live_heap.csv"
    for s in 12 10; do
        for algo in GraMi GraMiFilter WEGM WeLT; do
            WELT_LIVE_HEAP=on run_cell "$LIVE_OUT" datasets/email_eu.lg "$s" "$(tau_w_of email_eu)" "$algo" "$LIMIT" 0 1
        done
    done
    for cell in "email_eu 4" "email_eu 3" "lastfm 100" "lastfm 50"; do
        read -r ds s <<< "$cell"
        WELT_LIVE_HEAP=on run_cell "$LIVE_OUT" "datasets/$ds.lg" "$s" "$(tau_w_of "$ds")" WeLT "$LIMIT" 0 1
    done
    ;;
b3heap)
    # Not part of all. Smallest heap at which a cell completes (tools/min_heap.py): bisection on a
    # geometric scale down to 5% between a failing and a passing -Xmx, the other JVM options of
    # JAVA_OPTS kept; a probe passes when the cell runs at most twice as slow as with the full heap.
    # Cells: those of b3live. citeseer is left out: wherever GraMi completes there, every algorithm
    # uses a few MB. The floor of each dataset
    # (WeLT at a support no label reaches, so the run only loads the graph and builds the index)
    # separates the graph from the mining.
    require_voting_choice
    PY=$(find_python)
    HEAP_OUT="$RUN_DIR/B3_min_heap.csv"
    HEAP_LOG="$RUN_DIR/min_heap_logs"
    heap_cell() {
        local tw
        tw=$(tau_w_of "$1")
        if [ "${RESUME:-1}" = "1" ] && cell_done "$HEAP_OUT" "dataset=$1.lg" "algorithm=$3" "minSup=$2" "minWeight=$tw"; then
            echo "--- skip (already in $(basename "$HEAP_OUT")): min heap $3 $1 minSup=$2"
            return 0
        fi
        $PY tools/min_heap.py "$HEAP_OUT" "$HEAP_LOG" "datasets/$1.lg" "$2" "$tw" "$3" --first-limit-ms "$LIMIT"
        checkpoint "min heap $3 $1.lg minSup=$2: $(last_status_heap)"
    }
    last_status_heap() { awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) h[$i] = i } END { print $h["statusAtMax"] " ok=" $h["heapOkMB"] "MB" }' "$HEAP_OUT"; }
    for ds in email_eu lastfm; do heap_cell "$ds" 1000000000 WeLT; done
    for s in 12 10; do
        for algo in GraMi GraMiFilter WEGM WeLT; do heap_cell email_eu "$s" "$algo"; done
    done
    for cell in "email_eu 4" "email_eu 3" "lastfm 100" "lastfm 50"; do
        read -r ds s <<< "$cell"
        heap_cell "$ds" "$s" WeLT
    done
    ;;
b2hard|b4hard)
    # Hard cells, rule fixed before any run: for each dataset, the smallest B1 support at which
    # WeLT completed with a median run time of at most 60 s (tools/b5_choose.py
    # smallest-ok-within). A cell already measured by b2grid / b4 is not repeated.
    require_voting_choice
    B1="$RUN_DIR/B1_efficiency.csv"
    CHOICES="$RUN_DIR/hard_cells.txt"
    if [ "$CMD" = "b2hard" ]; then
        OUT="$RUN_DIR/B2_voting_grid_hard.csv"; DSETS="citeseer email_eu lastfm mico string_ecoli"
        DONE="citeseer:250 citeseer:200 email_eu:12 email_eu:10 lastfm:600 mico:15000 string_ecoli:1900"
    else
        OUT="$RUN_DIR/B4_ablation_hard.csv"; DSETS="citeseer email_eu lastfm"
        DONE="citeseer:250 citeseer:200 citeseer:150 email_eu:12 email_eu:10 email_eu:8 lastfm:600 lastfm:400"
    fi
    for ds in $DSETS; do
        s=$($PY tools/b5_choose.py smallest-ok-within "$B1" "$ds.lg" WeLT 60000 | tr -d '\r')
        grep -q "^$CMD $ds " "$CHOICES" 2>/dev/null \
            || echo "$CMD $ds minSup=${s:-none} (smallest B1 support where WeLT completed within 60 s)" >> "$CHOICES"
        if [ -z "$s" ]; then echo "--- $CMD $ds: no B1 support qualifies"; continue; fi
        case " $DONE " in *" $ds:$s "*) echo "--- $CMD $ds minSup=$s: already measured"; continue ;; esac
        tw=$(tau_w_of "$ds")
        if [ "$CMD" = "b2hard" ]; then
            for v in $(voter_grid); do
                WELT_VOTER="$v" WELT_WORDER=on run_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" WeLT "$LIMIT" "$WARMUP" "$MEASURED"
            done
        else
            run_ablation_cell "$OUT" "datasets/$ds.lg" "$s" "$tw" "$LIMIT" "$WARMUP" "$MEASURED"
        fi
    done
    ;;
wegmrange)
    # WEGM where it returns patterns. WEGM counts only occurrences with the same (integer) edge
    # weights, so it returns a pattern iff some weighted edge type of weight >= tau_w has MNI >=
    # minSup (results-invariant/wegm-single-edge-support-20261021); at the b1 supports it returns
    # none except at mico 14000. Cells, fixed before any run of this campaign: for each dataset
    # m = the largest MNI of such a type (email_eu 2, citeseer 5, lastfm 12, string_ecoli 345,
    # github 5352, mico 14826), and ceil(m/2) for citeseer and lastfm, whose heavy subgraphs have
    # under 100 edges. At each cell: WEGM, the exact ALL score (GraMi on the heavy subgraph) and
    # WeLT, one run each (no warm-up): the step compares result sets (b7 checks that the skeleton of
    # every WEGM pattern is an ALL pattern and every ALL pattern a WeLT pattern), not run times. ALL
    # and WeLT run with the limit WEGM_RANGE_LIMIT_MS (default 600000).
    require_voting_choice
    RLIM="${WEGM_RANGE_LIMIT_MS:-600000}"
    for cell in "email_eu 2" "citeseer 5" "citeseer 3" "lastfm 12" "lastfm 6" "string_ecoli 345" "github 5352" "mico 14826"; do
        read -r ds s <<< "$cell"
        tw=$(tau_w_of "$ds")
        run_cell "$RUN_DIR/B6_wegm_range.csv" "datasets/$ds.lg" "$s" "$tw" WEGM "$LIMIT" 0 1
        run_all_exact_cell "$RUN_DIR/B6_wegm_range_all.csv" "datasets/$ds.lg" "$s" "$tw" "$RLIM" 0 1 "$RUN_DIR/patterns_wegm_range_all"
        run_cell "$RUN_DIR/B6_wegm_range.csv" "datasets/$ds.lg" "$s" "$tw" WeLT "$RLIM" 0 1
    done
    ;;
b5)
    # tau_w sweep, rule fixed before any run (see tools/b5_choose.py):
    #  comparison sweep  minSup = smallest support of the B1 range at which GraMi completed (the
    #                    tau_w -> 0 limit, so every tau_w point is at most as hard); if none, the
    #                    first value of the extension list at which a GraMi probe completes in 60 s.
    #                    Algorithms GraMiFilter, WEGM, WeLT and the exact ALL score per
    #                    point, plus WeLT with one shared table.
    #  WeLT-only sweep   minSup = smallest support of the B1 range at which WeLT completed; result
    #                    counts and sizes only (no baseline at that support).
    #  tau_w grid        rho = 0.1 ... 0.9 of the weight range, plus the dataset's tau_w.
    require_voting_choice
    B1="$RUN_DIR/B1_efficiency.csv"
    OUT="$RUN_DIR/B5_tau_sweep.csv"
    SWEEP="$RUN_DIR/B5_shared_table.csv"
    ONLY="$RUN_DIR/B5_welt_only.csv"
    PROBE="$RUN_DIR/B5_grami_probe.csv"
    CHOICES="$RUN_DIR/B5_choices.txt"
    for row in "${B1_CELLS[@]:0:3}"; do
        read -r ds sups <<< "$row"
        if [ -n "${2:-}" ] && [ "$ds" != "$2" ]; then continue; fi
        tw=$(tau_w_of "$ds")
        for v in $sups; do
            if [ "$($PY tools/b5_choose.py status "$B1" "$ds.lg" GraMi "$v" | tr -d '\r')" = "none" ]; then
                echo "ERROR: B1 has no GraMi row for $ds minSup=$v; run ./revision.sh b1 $ds first" >&2
                exit 1
            fi
        done
        s=$($PY tools/b5_choose.py smallest-ok "$B1" "$ds.lg" GraMi | tr -d '\r')
        how="smallest B1 support at which GraMi completed"
        if [ -z "$s" ]; then
            case "$ds" in
                citeseer) ext="600 900 1350 2000" ;; email_eu) ext="30 45 68 100" ;; lastfm) ext="900 1350 2000 3000" ;;
            esac
            for v in $ext; do
                run_cell "$PROBE" "datasets/$ds.lg" "$v" "$tw" GraMi 60000 0 1
                if [ "$($PY tools/b5_choose.py status "$PROBE" "$ds.lg" GraMi "$v" | tr -d '\r')" = "OK" ]; then s="$v"; break; fi
            done
            how="first support of the extension list ($ext) at which a GraMi probe completed within 60 s"
            if [ -z "$s" ]; then
                echo "ERROR: GraMi completed at no support of the extension list $ext for $ds" >&2
                checkpoint "b5 $ds FAILED: no support found for the comparison sweep"
                exit 1
            fi
        fi
        sw=$($PY tools/b5_choose.py smallest-ok "$B1" "$ds.lg" WeLT | tr -d '\r')
        taus=$(java -cp target/classes welt.runner.SharedTableSweepMain --grid "datasets/$ds.lg" \
            0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9 "$tw" | tr -d '\r' | paste -sd, -)
        grep -q "^$ds " "$CHOICES" 2>/dev/null || {
            echo "$ds comparison minSup=$s ($how)"
            echo "$ds WeLT-only minSup=${sw:-none} (smallest B1 support at which WeLT completed)"
            echo "$ds tau_w=$taus"
        } >> "$CHOICES"
        echo "=== B5 $ds comparison minSup=$s, WeLT-only minSup=${sw:-none}, tau_w=$taus"
        for t in ${taus//,/ }; do
            for algo in GraMiFilter WEGM WeLT; do
                run_cell "$OUT" "datasets/$ds.lg" "$s" "$t" "$algo" "$LIMIT" "$WARMUP" "$MEASURED"
            done
            run_all_exact_cell "$RUN_DIR/B5_all_exact.csv" "datasets/$ds.lg" "$s" "$t" "$LIMIT" "$WARMUP" "$MEASURED" "$RUN_DIR/patterns_all_exact"
        done
        if cell_done "$SWEEP" "dataset=$ds.lg" "minSup=$s" "row=sweep"; then
            echo "--- skip (already in $(basename "$SWEEP")): shared-table sweep $ds minSup=$s"
        else
            tmp=$(mktemp)
            BENCH_CSV=1 java ${JAVA_OPTS:-} -cp target/classes welt.runner.SharedTableSweepMain \
                "datasets/$ds.lg" "$s" "$taus" "$LIMIT" "$WARMUP" "$MEASURED" > "$tmp" 2>&1 || true
            cat "$tmp"
            append_csv "$tmp" "$SWEEP"
            rm -f "$tmp"
            checkpoint "shared-table sweep $ds minSup=$s: $(last_status "$SWEEP")"
        fi
        if [ -n "$sw" ] && [ "$sw" != "$s" ]; then
            for t in ${taus//,/ }; do
                run_cell "$ONLY" "datasets/$ds.lg" "$sw" "$t" WeLT "$LIMIT" "$WARMUP" "$MEASURED"
            done
        fi
    done
    ;;
downstream)
    require_voting_choice
    "${DOWNSTREAM_PYTHON:-$PY}" tools/downstream/b6.py --out "$RUN_DIR/B8_downstream" --kind measurement \
        --voting "$RUN_DIR/voting_choice.json" --limit-ms "$LIMIT" --warmup "$WARMUP" --measured "$MEASURED" --skip-build
    ;;
b7)
    # Required (exit 1 on failure): GraMiFilter = WeLT; the exact ALL result
    # (GraMi on the heavy subgraph) = the ALL set computed in memory from its definition, and
    # contained in WeLT (the containment proposition); at the wegmrange cells, the skeleton of every
    # WEGM pattern is an ALL pattern and every ALL pattern a WeLT pattern (B7_nesting.csv; a cell
    # where a dump is missing or empty is listed as not compared, and at least one cell must be
    # compared). Recorded only, when present: ReSuM's
    # released code against the ALL definition and against WeLT (its weight check differs from the
    # definition on cyclic patterns, see results-probe/resum-cycle-check-20261019).
    P="$RUN_DIR/patterns"
    Q="$RUN_DIR/patterns_b7_all"
    rm -rf "$Q"; mkdir -p "$Q"
    for f in "$RUN_DIR"/patterns_all_exact/*_GraMi_*.tsv; do
        [ -e "$f" ] || continue
        base=$(basename "$f" .tsv)
        tw=${base##*_}; rest=${base%_*}; s=${rest##*_}; rest=${rest%_GraMi_*}; ds=${rest%_heavy_*}
        cp "$f" "$Q/${ds}_ALL-exact_${s}_${tw}.tsv"
        java ${JAVA_OPTS:-} -cp target/classes welt.runner.ResumMain definition "datasets/$ds.lg" "$s" "$tw" "$Q" > /dev/null
        [ -e "$P/${ds}_WeLT_${s}_${tw}.tsv" ] && cp "$P/${ds}_WeLT_${s}_${tw}.tsv" "$Q/"
    done
    for f in "$P"/*_ReSuM-ALL_*.tsv; do
        [ -e "$f" ] || continue
        base=$(basename "$f" .tsv)
        tw=${base##*_}; rest=${base%_*}; s=${rest##*_}; ds=${rest%_ReSuM-ALL_*}
        [ -e "$P/${ds}_ALL-definition_${s}_${tw}.tsv" ] || \
            java ${JAVA_OPTS:-} -cp target/classes welt.runner.ResumMain definition "datasets/$ds.lg" "$s" "$tw" "$P" > /dev/null
    done
    status=0
    b7_compare() { # <dir> <out.csv> <flag or -> <A> <B> <what> <required|recorded>
        local code=0
        if [ "$3" = "-" ]; then $PY tools/compare_patterns.py "$1" "$2" "$4" "$5" || code=$?
        else $PY tools/compare_patterns.py "$3" "$1" "$2" "$4" "$5" || code=$?; fi
        if [ "$7" = "recorded" ]; then
            [ "$code" -eq 0 ] || echo "NOTE: $6 does not hold on every cell (exit $code, see $(basename "$2"))"
            return 0
        fi
        if [ "$code" -eq 1 ]; then echo "ERROR: $6 fails on some cell (see $(basename "$2"))" >&2; status=1; fi
        if [ "$code" -eq 2 ]; then echo "ERROR: $6: no cell could be compared" >&2; status=1; fi
    }
    b7_compare "$P" "$RUN_DIR/B7_equivalence.csv" - GraMiFilter WeLT "GraMiFilter = WeLT" required
    b7_compare "$Q" "$RUN_DIR/B7_all_exact_definition.csv" - ALL-exact ALL-definition "exact ALL = ALL definition" required
    b7_compare "$Q" "$RUN_DIR/B7_all_within_welt.csv" --subset ALL-exact WeLT "exact ALL within WeLT" required
    if [ -f "$RUN_DIR/B6_wegm_range.csv" ]; then
        NEST="$RUN_DIR/B7_nesting.csv"
        echo "dataset,minSup,minWeight,wegmSkeletons,all,welt,skeletonsNotInAll,allNotInWelt,exit" > "$NEST"
        compared=0
        for cell in "email_eu 2" "citeseer 5" "citeseer 3" "lastfm 12" "lastfm 6" "string_ecoli 345" "github 5352" "mico 14826"; do
            read -r ds s <<< "$cell"
            tw=$(tau_w_of "$ds")
            code=0
            out=$(java ${JAVA_OPTS:-} -cp target/classes welt.runner.NestingCheckMain "$P/${ds}_WEGM_${s}_${tw}.0.tsv" \
                "$RUN_DIR/patterns_wegm_range_all/${ds}_heavy_${tw}_GraMi_${s}_${tw}.0.tsv" "$P/${ds}_WeLT_${s}_${tw}.0.tsv") || code=$?
            echo "$ds minSup=$s: $out" | grep -v '^NESTING_CSV' || true
            line=$(printf '%s\n' "$out" | tr -d '\r' | sed -n 's/^NESTING_CSV,//p')
            echo "$ds.lg,$s,$tw,${line:-,,,,},$code" >> "$NEST"
            if [ "$code" -eq 1 ]; then echo "ERROR: nesting fails at $ds minSup=$s (see $(basename "$NEST"))" >&2; status=1; fi
            if [ "$code" -eq 0 ]; then compared=$((compared + 1)); fi
        done
        echo "nesting: $compared cells compared"
        if [ "$compared" -eq 0 ]; then echo "ERROR: nesting: no cell could be compared" >&2; status=1; fi
    fi
    if ls "$P"/*_ReSuM-ALL_*.tsv > /dev/null 2>&1; then
        b7_compare "$P" "$RUN_DIR/B7_resum_definition.csv" - ReSuM-ALL ALL-definition "ReSuM code = ALL definition" recorded
        b7_compare "$P" "$RUN_DIR/B7_resum_within_welt.csv" --subset ReSuM-ALL WeLT "ReSuM code within WeLT" recorded
    fi
    ;;
*)
    echo "unknown command: $CMD" >&2
    exit 1
    ;;
esac

java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain finish "$META"
checkpoint "$CMD ${2:-} done"
echo "Done: $CMD -> $RUN_DIR"
if [ "$CMD" = "b7" ]; then exit "$status"; fi
