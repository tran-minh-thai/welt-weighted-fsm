#!/usr/bin/env bash
# Scenario-based experiment driver. The full comparison is split into independent SCENARIOS
# so it can be run in parts (one evening / one overnight at a time) instead of one very long
# unattended session. Each scenario writes its OWN timestamped CSV under results/:
#
#       results/SC<n>_<YYYYMMDD_HHMMSS>_<scenario-name>.csv
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
# Why the split also helps aggregation: sc1/sc2 contain only the edge-weight algorithms whose
# result sets are comparable (WEGM and WeLT share the SAME #FWS; GraMi is the unweighted
# reference). OWGraMi (vertex weights) lives alone in sc3. Every CSV additionally carries a
# `weightModel` column (none/edge/vertex) as a second safeguard.
#
# Usage:
#   ./experiments.sh list                 # describe the scenarios and exit
#   ./experiments.sh sc1                  # run scenario 1
#   ./experiments.sh sc2 bitcoin          # run scenario 2, only datasets whose path matches "bitcoin"
#   ./experiments.sh all                  # run sc1, sc2, sc3 sequentially
#
# Each (dataset, minSup) cell is timed with BenchmarkMain: warmup runs are discarded and the
# MEDIAN of MEASURED runs is reported; a run exceeding the per-run limit is recorded as TO.
# With the default 60-minute limit there is no need to find timeouts and re-run them.
#
# Tunable via environment variables:
#   TIME_LIMIT_MS  per-run wall-clock limit (default 3600000 = 60 min)
#   WARMUP         warmup runs to discard (default 1)
#   MEASURED       measured runs for the median (default 5)
#   JAVA_OPTS      extra JVM flags, e.g. JAVA_OPTS="-Xmx8g" for large graphs / low-RAM machines
set -euo pipefail
cd "$(dirname "$0")"

LIMIT="${TIME_LIMIT_MS:-3600000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"

# Fill SC_NUM / SC_NAME / SC_ALGOS / SC_CONFIGS for the requested scenario.
# Each CONFIGS row is:  <dataset> <minWeight tau_w> <search budget> <minSup values...>
# (minWeight/minSup absolute scales differ per dataset; report them as ratios when writing up.)
scenario_def() {
    case "$1" in
        sc1)
            SC_NUM=1; SC_NAME="efficiency"; SC_ALGOS="GraMi,WEGM,WeLT"
            # The WeLT/WEGM gap is negligible at high support (both finish in ms) and explodes at
            # low support (WeLT's candidate set stays bounded by tau_w selectivity while WEGM's
            # grows with the lattice). The sweep therefore spans a high "no-overhead" anchor down
            # to where the baseline times out, to trace the full speedup-vs-support curve.
            SC_CONFIGS=(
                "datasets/citeseer.lg  96   800  400 300 250 200 150 100"
                "datasets/email_eu.lg  336  400  20 15 12 10 8 6"
                "datasets/lastfm.lg    40   400  600 500 400 300 200"
            ) ;;
        sc2)
            SC_NUM=2; SC_NAME="scalability"; SC_ALGOS="GraMi,WEGM,WeLT"
            # Large / dense graphs. Here the per-candidate MNI cost on the big graph dominates, so
            # at a completable support all methods converge (WeLT's candidate-pruning advantage only
            # shows in the candidate-bound regime of sc1). These runs demonstrate WeLT SCALES to a
            # 100K-vertex graph (mico ~24s), a 2-label 37K-vertex graph (github) and a dense graph
            # (string_ecoli). mico and github need a large heap: run with JAVA_OPTS="-Xmx20g".
            SC_CONFIGS=(
                "datasets/mico.lg          100  2000  16000 15000 14000"
                "datasets/github.lg        30   2000  26000 25000 24000"
                "datasets/string_ecoli.lg  750  400   2000 1900 1850"
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
                "datasets/citeseer.lg  78   400  400 300 200"
                "datasets/email_eu.lg  75   400  20 15 10"
            ) ;;
        *) echo "Unknown scenario: $1 (expected sc1|sc2|sc3)" >&2; return 1 ;;
    esac
}

print_list() {
    cat <<'EOF'
Scenarios (each writes results/SC<n>_<YYYYMMDD_HHMMSS>_<name>.csv):

  sc1  efficiency           GraMi,WEGM,WeLT   citeseer, email_eu, lastfm
                            the advantage: at low support WeLT completes while baselines time out.
  sc2  scalability          GraMi,WEGM,WeLT   mico, github, string_ecoli
                            WeLT scales to large/dense graphs (needs JAVA_OPTS="-Xmx20g").
  sc3  vertex_generality    OWGraMi           citeseer, email_eu
                            framework generality; DIFFERENT (vertex-weight) result set with its
                            OWN tau_w (~ vertex-weight median); kept separate -- do NOT read its
                            #FWS as a speedup baseline. (lastfm omitted: no clean operating point.)
  all                       run sc1, sc2, sc3 sequentially.

Usage: ./experiments.sh <sc1|sc2|sc3|all> [dataset-filter]
Env:   TIME_LIMIT_MS (default 3600000 = 60 min), WARMUP, MEASURED, JAVA_OPTS
EOF
}

run_scenario() {
    local sc="$1" filter="${2:-}"
    scenario_def "$sc" || exit 1
    local stamp out
    stamp=$(date +%Y%m%d_%H%M%S)
    out="results/SC${SC_NUM}_${stamp}_${SC_NAME}.csv"
    mkdir -p results
    echo "dataset,algorithm,weightModel,minSup,minWeight,budget,limitMs,freq,candMNI,mineMNI,medianMs,timedOut" > "$out"
    echo "=== Scenario SC${SC_NUM} (${SC_NAME}) | algos=${SC_ALGOS} | limit=${LIMIT}ms ==="
    echo "    -> $out"
    for cfg in "${SC_CONFIGS[@]}"; do
        read -r ds tw budget sups <<< "$cfg"
        if [ -n "$filter" ] && [[ "$ds" != *"$filter"* ]]; then continue; fi
        for s in $sups; do
            echo ">>> $ds  minSup=$s  tau_w=$tw  budget=$budget  limit=${LIMIT}ms"
            tmp=$(mktemp)
            BENCH_CSV=1 BENCH_ALGOS="$SC_ALGOS" java ${JAVA_OPTS:-} -cp target/classes \
                welt.runner.BenchmarkMain "$ds" "$s" "$tw" "$budget" "$LIMIT" "$WARMUP" "$MEASURED" > "$tmp" 2>&1 || true
            cat "$tmp"
            grep '^CSV,' "$tmp" | grep -v 'CSV,dataset' | sed 's/^CSV,//' >> "$out" || true
            rm -f "$tmp"
        done
    done
    echo "--- SC${SC_NUM} done -> $out ---"
    echo
}

CMD="${1:-list}"
if [ "$CMD" = "list" ] || [ "$CMD" = "-h" ] || [ "$CMD" = "--help" ]; then
    print_list
    exit 0
fi

[ -d target/classes ] || ./build.sh

# Keep the machine awake for the whole (possibly multi-hour) run. On macOS, caffeinate holds
# an idle-sleep assertion and exits automatically when this script (PID $$) ends. No-op on
# systems without caffeinate (e.g. Linux).
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ &
fi

case "$CMD" in
    all) for sc in sc1 sc2 sc3; do run_scenario "$sc" "${2:-}"; done ;;
    sc1|sc2|sc3) run_scenario "$CMD" "${2:-}" ;;
    *) echo "Unknown command: $CMD" >&2; print_list; exit 1 ;;
esac

echo "Done."
