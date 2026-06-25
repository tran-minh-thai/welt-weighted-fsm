#!/usr/bin/env bash
# Compile all main sources + tests with javac (no Maven required).
# When Maven is installed, you can use: mvn -q compile test-compile
set -euo pipefail
cd "$(dirname "$0")"

JUNIT="tools/junit-console.jar"
RELEASE="${JAVA_RELEASE:-17}"

# Fetch the standalone JUnit 5 console launcher on first run (not committed to git).
JUNIT_VERSION="1.10.2"
JUNIT_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"
if [ ! -f "$JUNIT" ]; then
    echo "[build] downloading JUnit console standalone ${JUNIT_VERSION} ..."
    mkdir -p tools
    curl -sSL -o "$JUNIT" "$JUNIT_URL"
fi

echo "[build] compiling main (Java $RELEASE) ..."
rm -rf target/classes && mkdir -p target/classes
javac --release "$RELEASE" -encoding UTF-8 -d target/classes \
    $(find src/main/java -name "*.java")

echo "[build] compiling tests ..."
rm -rf target/test-classes && mkdir -p target/test-classes
javac --release "$RELEASE" -encoding UTF-8 -cp "target/classes:$JUNIT" -d target/test-classes \
    $(find src/test/java -name "*.java")

echo "[build] OK"
