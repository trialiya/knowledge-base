#!/usr/bin/env bash
# Launch the Knowledge Base backend JAR on Linux or macOS.
# Edit app.env before running.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/app.env"
JAR="$SCRIPT_DIR/../backend/build/libs/backend-1.0-SNAPSHOT.jar"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: config file not found: $ENV_FILE" >&2
  exit 1
fi

if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found: $JAR" >&2
  echo "Build first:  ./gradlew :backend:bootJar" >&2
  exit 1
fi

# Load env file (skip comments and blank lines)
set -a
# shellcheck disable=SC1090
source <(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$')
set +a

export LANG="${LANG:-C.utf8}"
export LC_ALL="${LC_ALL:-C.utf8}"

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

echo "Starting Knowledge Base..."
echo "  JAR:     $JAR"
echo "  Profile: ${SPRING_PROFILES_ACTIVE:-default}"
echo "  Project: ${PROJECT_PATH:-.}"
echo ""

# shellcheck disable=SC2086
exec "$JAVA_BIN" --enable-preview ${JAVA_OPTS:-} -jar "$JAR"
