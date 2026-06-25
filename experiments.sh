#!/usr/bin/env bash
# Run the full experimental comparison (all four algorithms × datasets × support
# thresholds) and aggregate the results into results/experiments.csv.
#
# Each (dataset, minSup, algorithm) cell is timed with BenchmarkMain: warmup runs are
# discarded and the MEDIAN of repeated measured runs is reported; any run exceeding the
# per-run time limit is recorded as a timeout (TO). Counting metrics are deterministic,
# so they are taken from a single run.
#
# Tunable via environment variables:
#   TIME_LIMIT_MS  per-run wall-clock limit (default 60000 = 60s; use 3600000 for 1 hour)
#   WARMUP         warmup runs to discard (default 1)
#   MEASURED       measured runs for the median (default 5)
#
# Each row of CONFIGS is:  <dataset> <minWeight τ_w> <search budget> <minSup values...>
# The minWeight and minSup values are chosen per dataset (their absolute scales differ);
# express them as ratios (σ_s = minSup/|V|, ρ_w within the weight range) when reporting.
set -euo pipefail
cd "$(dirname "$0")"

[ -d target/classes ] || ./build.sh

LIMIT="${TIME_LIMIT_MS:-60000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"
OUT="results/experiments.csv"
mkdir -p results

CONFIGS=(
  "datasets/citeseer.lg     96   800  500 400 300 200"
  "datasets/email_eu.lg     336  400  25 20 15 10"
  "datasets/lastfm.lg       40   400  600 500 400 300"
  "datasets/string_ecoli.lg 900  200  600 500 400"
  "datasets/bitcoin_otc.lg  17   200  1100 1000 900"
)

echo "dataset,algorithm,minSup,minWeight,budget,limitMs,freq,candMNI,mineMNI,medianMs,timedOut" > "$OUT"

for cfg in "${CONFIGS[@]}"; do
    read -r ds tw budget sups <<< "$cfg"
    for s in $sups; do
        echo ">>> $ds  minSup=$s  tau_w=$tw  budget=$budget  limit=${LIMIT}ms"
        tmp=$(mktemp)
        BENCH_CSV=1 java -cp target/classes welt.runner.BenchmarkMain \
            "$ds" "$s" "$tw" "$budget" "$LIMIT" "$WARMUP" "$MEASURED" > "$tmp" 2>&1 || true
        cat "$tmp"
        grep '^CSV,' "$tmp" | grep -v 'CSV,dataset' | sed 's/^CSV,//' >> "$OUT" || true
        rm -f "$tmp"
    done
done

echo
echo "Done. Aggregated results written to $OUT"
