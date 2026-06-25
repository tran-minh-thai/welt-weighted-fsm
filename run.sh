#!/usr/bin/env bash
# Run one algorithm on one dataset at a given support threshold.
# Example: ./run.sh datasets/citeseer.lg 500 GraMi
set -euo pipefail
cd "$(dirname "$0")"

DATASET="${1:-datasets/citeseer.lg}"
MINSUP="${2:-500}"
ALGO="${3:-GraMi}"

if [ ! -d target/classes ]; then ./build.sh; fi
# JAVA_OPTS lets you pass extra JVM flags, e.g. JAVA_OPTS="-Xmx8g" for large graphs.
java ${JAVA_OPTS:-} -cp target/classes welt.runner.MineMain "$DATASET" "$MINSUP" "$ALGO"
