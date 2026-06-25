#!/usr/bin/env bash
# Run ALL tests with a single command (JUnit 5 standalone). Equivalent to `mvn test`.
set -euo pipefail
cd "$(dirname "$0")"

./build.sh
echo "[test] running JUnit ..."
java -jar tools/junit-console.jar execute \
    -cp "target/classes:target/test-classes" \
    --scan-classpath --disable-banner --details=tree
