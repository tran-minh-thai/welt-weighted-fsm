#!/usr/bin/env bash
# Threshold calibration probe. For each large/dense graph it runs WeLT only (the efficient
# strategy -- if WeLT is fast the slower baselines are even slower, so WeLT's runtime is the
# floor) at a DESCENDING minSup sweep with a per-probe cap, stopping once WeLT times out.
# The goal is to locate the minSup band where mining does MEANINGFUL work (seconds to a few
# minutes) instead of finishing in milliseconds; the scenario thresholds in experiments.sh are
# then set from the reported times.
#
#   PROBE_CAP_MS   per-probe wall-clock cap (default 180000 = 3 min)
#   JAVA_OPTS      JVM flags (default -Xmx20g for the 100K-vertex / dense graphs)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/classes ] || ./build.sh
CAP="${PROBE_CAP_MS:-600000}"
: "${JAVA_OPTS:=-Xmx7g}"; export JAVA_OPTS   # 4 concurrent JVMs on 32 GB -> ~7 GB each is safe

# graph | tau_w (~ median weight, selective) | budget | descending minSup list
# Round 3: fine sweep AROUND each sharp cliff found in round 2, with a 10-min cap, to pin the
# lowest minSup at which WeLT still completes (meaningful minutes of work, not milliseconds).
PROBES=(
  "datasets/mico.lg          100  2000  14000 13000 12000 11000"
  "datasets/github.lg        30   2000  23000 21000 20000"
  "datasets/string_ecoli.lg  750  400   1900 1800 1700 1600"
  "datasets/lastfm.lg        20   400   2000 1600 1300"
)

# Probe one graph: descend its support list, stop at the first WeLT timeout.
probe_one() {
    local ds="$1" tw="$2" budget="$3"; shift 3
    local tag; tag=$(basename "$ds" .lg)
    local out="calibrate.$tag.out"; : > "$out"
    for s in "$@"; do
        csv=$(BENCH_CSV=1 BENCH_ALGOS=WeLT java ${JAVA_OPTS} -cp target/classes \
                welt.runner.BenchmarkMain "$ds" "$s" "$tw" "$budget" "$CAP" 0 1 2>&1 \
              | awk -F, '/^CSV,/ && $3=="WeLT"{print $9, $12, $13}')
        read -r fws tm to <<< "${csv:-? ? ?}"
        printf "%-18s minSup=%-7s tau_w=%-5s #FWS=%-9s time(ms)=%-12s TO=%s\n" \
            "$tag" "$s" "$tw" "$fws" "$tm" "$to" >> "$out"
        [ "$to" != "-" ] && { echo "    ^ T.O. at minSup=$s -> use the support just ABOVE as the meaningful+completable point." >> "$out"; break; }
    done
}

# Run the four graphs concurrently (one JVM each).
for row in "${PROBES[@]}"; do
    read -r ds tw budget sups <<< "$row"
    probe_one "$ds" "$tw" "$budget" $sups &
done
wait

echo "===== CALIBRATION (higher supports, ${CAP}ms cap, 4 graphs in parallel) ====="
for tag in mico github string_ecoli lastfm; do cat "calibrate.$tag.out" 2>/dev/null; echo; done
echo "Pick, per graph, the lowest minSup whose WeLT time is meaningful (minutes) yet finishes;"
echo "the real 60-min run can also include one support lower than that to push WeLT to its frontier."
