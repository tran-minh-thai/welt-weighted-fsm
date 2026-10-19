#!/usr/bin/env bash
# Scenario-based experiment driver. The full comparison is split into independent SCENARIOS
# so it can be run in parts (one evening / one overnight at a time) instead of one very long
# unattended session. All scenarios of a campaign write into ONE run directory:
#
#       results/v2_revision/<YYYYMMDD_HHMM>_<host>/SC<n>_<scenario-name>.csv
#       results/v2_revision/<YYYYMMDD_HHMM>_<host>/meta_<datetime>_<command>.json  (machine, JVM, commit)
#       results/v2_revision/<YYYYMMDD_HHMM>_<host>/topology.csv                    (dataset properties)
#
# Set RUN_DIR=<that directory> to add scenarios to an existing campaign. The script refuses
# to run from a modified working tree (the commit in meta.json must be the code that ran).
#
# Scenarios:
#   sc1  efficiency          GraMi,WEGM,WeLT   citeseer, email_eu, lastfm
#                            -> the advantage: at low support WeLT completes while baselines T.O.
#   sc2  scalability         GraMi,WEGM,WeLT   mico, github, string_ecoli
#                            -> WeLT scales to large/dense graphs (per-candidate MNI cost dominates).
#   sc3  vertex_generality   OWGraMi           citeseer, email_eu
#                            -> framework-generality run with OWGraMi's OWN tau_w (~ vertex-weight
#                               median); a DIFFERENT (vertex-weight) result set kept in its own
#                               file so it is never mistaken for a speedup baseline.
#
# Result sets: EdgeTypeBound and WeLT solve the same problem (identical result sets); GraMi is
# the unweighted reference; WEGM treats edge weights as pattern labels (a different result set,
# efficiency comparison only); OWGraMi (vertex weights) lives alone in sc3. Every CSV carries a
# `weightModel` column (none / edge / edge-label / vertex) as a safeguard.
#
# Usage:
#   ./experiments.sh list                 # describe the scenarios and exit
#   ./experiments.sh sc1                  # run scenario 1
#   ./experiments.sh sc2 bitcoin          # run scenario 2, only datasets whose path matches "bitcoin"
#   ./experiments.sh all                  # run sc1, sc2, sc3 sequentially
#
# Each (dataset, minSup, algorithm) cell runs in its own JVM (tools/common.sh run_cell): warmup
# runs are discarded and the MEDIAN of MEASURED runs is reported. The status column is OK,
# TO (per-run limit reached), OOM (OutOfMemoryError) or CRASH (the JVM died without a row).
# With the default 60-minute limit there is no need to find timeouts and re-run them.
#
# Tunable via environment variables:
#   TIME_LIMIT_MS  per-run wall-clock limit (default 3600000 = 60 min)
#   WARMUP         warmup runs to discard (default 1)
#   MEASURED       measured runs for the median (default 5)
#   JAVA_OPTS      extra JVM flags, e.g. JAVA_OPTS="-Xmx8g" for large graphs / low-RAM machines
set -euo pipefail
cd "$(dirname "$0")"
. tools/common.sh

LIMIT="${TIME_LIMIT_MS:-3600000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"

# Fill SC_NUM / SC_NAME / SC_ALGOS / SC_CONFIGS for the requested scenario.
# Each CONFIGS row is:  <dataset> <minWeight tau_w> <search budget> <minSup values...>
# The search budget is 0 (exact mode) in every row; a non-zero budget switches the run to
# approximate mode (MNI becomes a lower bound) and is recorded as mode=approx in the CSV.
# (minWeight/minSup absolute scales differ per dataset; report them as ratios when writing up.)
scenario_def() {
    case "$1" in
        sc1)
            SC_NUM=1; SC_NAME="efficiency"; SC_ALGOS="GraMi,EdgeTypeBound,WEGM,WeLT"
            # The WeLT/WEGM gap is negligible at high support (both finish in ms) and explodes at
            # low support (WeLT's candidate set stays bounded by tau_w selectivity while WEGM's
            # grows with the lattice). The sweep therefore spans a high "no-overhead" anchor down
            # to where the baseline times out, to trace the full speedup-vs-support curve.
            SC_CONFIGS=(
                "datasets/citeseer.lg  96   0  400 300 250 200 150 100"
                "datasets/email_eu.lg  336  0  20 15 12 10 8 6"
                "datasets/lastfm.lg    40   0  600 500 400 300 200"
            ) ;;
        sc2)
            SC_NUM=2; SC_NAME="scalability"; SC_ALGOS="GraMi,EdgeTypeBound,WEGM,WeLT"
            # Large / dense graphs. Here the per-candidate MNI cost on the big graph dominates, so
            # at a completable support all methods converge (WeLT's candidate-pruning advantage only
            # shows in the candidate-bound regime of sc1). These runs demonstrate WeLT SCALES to a
            # 100K-vertex graph (mico ~24s), a 2-label 37K-vertex graph (github) and a dense graph
            # (string_ecoli). mico and github need a large heap: run with JAVA_OPTS="-Xmx20g".
            SC_CONFIGS=(
                "datasets/mico.lg          100  0  16000 15000 14000"
                "datasets/github.lg        30   0  26000 25000 24000"
                "datasets/string_ecoli.lg  750  0   2000 1900 1850"
            ) ;;
        sc3)
            SC_NUM=3; SC_NAME="vertex_generality"; SC_ALGOS="OWGraMi"
            # OWGraMi mines with VERTEX weights (averaged incident edge weights), which sit on a
            # more compressed scale than edge weights -- so it needs its OWN tau_w (~ the vertex-
            # weight median: citeseer 78, email_eu 75), NOT the edge-weight tau_w of sc1 (with the
            # edge tau_w it returns the empty set). lastfm is omitted: its derived (Jaccard) vertex
            # weights have no clean operating point -- OWGraMi either explodes or returns nothing --
            # so it is not a meaningful generality showcase.
            SC_CONFIGS=(
                "datasets/citeseer.lg  78   0  400 300 200"
                "datasets/email_eu.lg  75   0  20 15 10"
            ) ;;
        *) echo "Unknown scenario: $1 (expected sc1|sc2|sc3)" >&2; return 1 ;;
    esac
}

print_list() {
    cat <<'EOF'
Scenarios (each writes results/v2_revision/<YYYYMMDD_HHMM>_<host>/SC<n>_<name>.csv):

  sc1  efficiency           GraMi,WEGM,WeLT   citeseer, email_eu, lastfm
                            the advantage: at low support WeLT completes while baselines time out.
  sc2  scalability          GraMi,WEGM,WeLT   mico, github, string_ecoli
                            WeLT scales to large/dense graphs (needs JAVA_OPTS="-Xmx20g").
  sc3  vertex_generality    OWGraMi           citeseer, email_eu
                            framework generality; DIFFERENT (vertex-weight) result set with its
                            OWN tau_w (~ vertex-weight median); kept separate -- do NOT read its
                            #FWS as a speedup baseline. (lastfm omitted: no clean operating point.)
  all                       run sc1, sc2, sc3 sequentially.
  ablation                  RQ4 per-component ablation (table / voting / weight-order) -> SC4 csv.
  memory                    RQ3 peak-memory comparison at completable supports -> SC5 csv.

Usage: ./experiments.sh <sc1|sc2|sc3|all|ablation|memory> [dataset-filter]
Env:   TIME_LIMIT_MS (default 3600000 = 60 min), WARMUP, MEASURED, JAVA_OPTS
EOF
}

run_scenario() {
    local sc="$1" filter="${2:-}"
    scenario_def "$sc" || exit 1
    local out="$RUN_DIR/SC${SC_NUM}_${SC_NAME}.csv"
    echo "=== Scenario SC${SC_NUM} (${SC_NAME}) | algos=${SC_ALGOS} | limit=${LIMIT}ms ==="
    echo "    -> $out"
    for cfg in "${SC_CONFIGS[@]}"; do
        read -r ds tw budget sups <<< "$cfg"
        if [ -n "$filter" ] && [[ "$ds" != *"$filter"* ]]; then continue; fi
        for s in $sups; do
            for algo in ${SC_ALGOS//,/ }; do
                run_cell "$out" "$ds" "$s" "$tw" "$algo" "$LIMIT" "$WARMUP" "$MEASURED" "$budget"
            done
        done
    done
    echo "--- SC${SC_NUM} done -> $out ---"
    echo
}

# Ablation study (RQ4): per-component contribution of WeLT, via AblationMain. Each config is
# chosen so that removing the lookup table ('no-table') is slow-but-completable, exposing the
# table's contribution. Writes $RUN_DIR/SC4_ablation.csv.
run_ablation() {
    local out="$RUN_DIR/SC4_ablation.csv"
    echo "=== Ablation (RQ4) | limit=${LIMIT}ms ==="
    echo "    -> $out"
    # <dataset> <minWeight tau_w> <minSup>
    local CFG=(
        "datasets/citeseer.lg  96   250"
        "datasets/citeseer.lg  96   200"
        "datasets/email_eu.lg  336  12"
    )
    for c in "${CFG[@]}"; do
        read -r ds tw s <<< "$c"
        run_ablation_cell "$out" "$ds" "$s" "$tw" "$LIMIT" "$WARMUP" "$MEASURED"
    done
    echo "--- ablation done -> $out ---"
}

# Peak-memory comparison (RQ3): GraMi/WEGM/WeLT at COMPLETABLE supports (all three finish, so
# the peak heap is comparable). Writes $RUN_DIR/SC5_memory.csv (peakMemMB column).
run_memory() {
    local out="$RUN_DIR/SC5_memory.csv"
    echo "=== Memory (RQ3) | limit=${LIMIT}ms ==="
    echo "    -> $out"
    local CFG=(
        "datasets/citeseer.lg  96   0  250 200"
        "datasets/email_eu.lg  336  0  12 10"
    )
    for cfg in "${CFG[@]}"; do
        read -r ds tw budget sups <<< "$cfg"
        for s in $sups; do
            for algo in GraMi EdgeTypeBound WEGM WeLT; do
                run_cell "$out" "$ds" "$s" "$tw" "$algo" "$LIMIT" 0 1 "$budget"
            done
        done
    done
    echo "--- memory done -> $out ---"
}

CMD="${1:-list}"
if [ "$CMD" = "list" ] || [ "$CMD" = "-h" ] || [ "$CMD" = "--help" ]; then
    print_list
    exit 0
fi

# Every run starts from a clean tree and a fresh build, so the commit in meta.json is the
# code that ran (no stale target/classes).
require_clean_tree
./build.sh
keep_awake
print_checklist

# One directory per campaign: results/v2_revision/<YYYYMMDD_HHMM>_<host>/. Set RUN_DIR to an
# existing directory to add scenarios to the same campaign (rows are appended).
HOST=$(hostname | tr -c 'A-Za-z0-9_.-\n' '_')
RUN_DIR="${RUN_DIR:-results/v2_revision/$(date +%Y%m%d_%H%M)_${HOST}}"
mkdir -p "$RUN_DIR"
echo "=== run directory: $RUN_DIR"
# Result sets of completed cells (exact MNI and W, written after the timed runs).
export DUMP_PATTERNS="${DUMP_PATTERNS:-$RUN_DIR/patterns}"
META="$RUN_DIR/meta_$(date +%Y%m%d_%H%M%S)_${CMD}.json"
java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain start "$META" \
    "command=experiments.sh $*" "limitMs=$LIMIT" "warmup=$WARMUP" "measured=$MEASURED"
PY=$(find_python)
if [ -n "$PY" ] && [ ! -f "$RUN_DIR/topology.csv" ]; then
    $PY tools/topology.py datasets "$RUN_DIR/topology.csv" || echo "WARNING: topology.py failed" >&2
elif [ -z "$PY" ]; then
    echo "WARNING: no Python 3 found; topology.csv not written (it can be generated on any machine)." >&2
fi

case "$CMD" in
    all) for sc in sc1 sc2 sc3; do run_scenario "$sc" "${2:-}"; done ;;
    sc1|sc2|sc3) run_scenario "$CMD" "${2:-}" ;;
    ablation|sc4) run_ablation ;;
    memory|sc5) run_memory ;;
    *) echo "Unknown command: $CMD" >&2; print_list; exit 1 ;;
esac

java ${JAVA_OPTS:-} -cp target/classes welt.runner.RunMetaMain finish "$META"
echo "Done. Results in $RUN_DIR"
