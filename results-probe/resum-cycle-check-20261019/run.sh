#!/usr/bin/env bash
# ReSuM's ALL score (released code) on cyclic patterns: the running example and seeded random graphs (probe).
# Run from the repository root:  results-probe/resum-cycle-check-20261019/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
D=results-probe/resum-cycle-check-20261019
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}" PYTHONDONTWRITEBYTECODE=1
PY=$(command -v python3 || command -v python)
./build.sh > /dev/null
"$PY" tools/build_resum.py > /dev/null
java -cp target/classes welt.runner.RunMetaMain start "$D/meta.json" \
    "command=$D/run.sh" "kind=probe (result sets only; times are not measurements)"
rm -rf "$D/example"; mkdir -p "$D/example"
for tw in 5 7; do
    "$PY" tools/run_resum.py "$D/example/resum.csv" datasets/example.lg 2 "$tw" 60000 1 1 "$D/example"
    java -cp target/classes welt.runner.ResumMain definition datasets/example.lg 2 "$tw" "$D/example"
    DUMP_PATTERNS="$D/example" BENCH_ALGOS=WeLT java $JAVA_OPTS -cp target/classes welt.runner.BenchmarkMain \
        datasets/example.lg 2 "$tw" 0 60000 0 1 > /dev/null 2>&1
done
"$PY" tools/compare_patterns.py "$D/example" "$D/example/resum_vs_definition.csv" ReSuM-ALL ALL-definition || true
"$PY" tools/compare_patterns.py --subset "$D/example" "$D/example/resum_within_welt.csv" ReSuM-ALL WeLT || true
rm -rf "$D/random"
"$PY" "$D/random_graphs.py" "$D/random" 150 | tail -1
java -cp target/classes welt.runner.RunMetaMain finish "$D/meta.json"
