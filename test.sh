#!/usr/bin/env bash
# Run ALL tests with a single command (JUnit 5 standalone). Equivalent to `mvn test`.
set -euo pipefail
cd "$(dirname "$0")"

# The tests read citeseer.lg and email_eu.lg, which are not committed: fetch and verify them first.
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then echo "ERROR: Python 3 is required to fetch the test graphs (tools/fetch_datasets.py)" >&2; exit 1; fi
"$PY" tools/fetch_datasets.py citeseer.lg email_eu.lg

./build.sh
echo "[test] running JUnit ..."
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=';' ;;
    *)                    CPSEP=':' ;;
esac
java -jar tools/junit-console.jar execute \
    -cp "target/classes${CPSEP}target/test-classes" \
    --scan-classpath --disable-banner --details=tree
