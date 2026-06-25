#!/usr/bin/env bash
# Convenience launcher: run any welt.runner.* class from ANY working directory.
# It changes into the project root first (so target/classes and datasets/ resolve),
# building once if needed, then forwards all arguments to the chosen runner.
#
# Examples:
#   ./welt.sh CompareMain   datasets/citeseer.lg 200 96 800
#   ./welt.sh BenchmarkMain datasets/email_eu.lg 10 336 400 60000
#   ./welt.sh MineMain      datasets/citeseer.lg 300 WeLT 95
#
# Pass extra JVM flags via JAVA_OPTS, e.g. JAVA_OPTS="-Xmx8g" ./welt.sh ...
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -lt 1 ]; then
    echo "Usage: ./welt.sh <RunnerClass> [args...]"
    echo "Runners:"; ls src/main/java/welt/runner/*.java | sed 's#.*/##; s#\.java##' | sed 's/^/  /'
    exit 1
fi

[ -d target/classes ] || ./build.sh

CLASS="$1"; shift
java ${JAVA_OPTS:-} -cp target/classes "welt.runner.$CLASS" "$@"
