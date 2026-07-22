#!/usr/bin/env bash
# Launch the Knowledge Base backend JAR on Linux or macOS.
#
# Usage:
#   ./run.sh [profile]
#
# Examples:
#   ./run.sh h2          # bundled H2 profile, zero external DB setup (default)
#   ./run.sh external    # PostgreSQL — provide your own application-external.yaml
#   ./run.sh internal    # copy application.yaml to application-internal.yaml,
#                         # edit it with your own values, then run with this profile
#
# Edit application.yaml (and, for a custom profile, the matching
# application-<profile>.yaml) before running.  The JVM is started from this
# directory so relative paths in application.yaml (e.g. project-path: ..)
# resolve against it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/../backend/build/libs/backend-1.0-SNAPSHOT.jar"
PROFILE="${1:-h2}"

if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found: $JAR" >&2
  echo "Build first:  ./gradlew :backend:bootJar" >&2
  exit 1
fi

if [ ! -f "$SCRIPT_DIR/application.yaml" ]; then
  echo "ERROR: $SCRIPT_DIR/application.yaml not found — fill in your settings." >&2
  exit 1
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

export LANG="${LANG:-C.utf8}"
export LC_ALL="${LC_ALL:-C.utf8}"

JAVA_OPTS="${JAVA_OPTS:--Xmx150m}"

echo "Starting Knowledge Base..."
echo "  Profile: $PROFILE"
echo "  Config:  $SCRIPT_DIR/application.yaml + application-$PROFILE.yaml"
echo "  JAR:     $JAR"
echo "  JAVA:    $JAVA_BIN"
echo ""

cd "$SCRIPT_DIR"

# shellcheck disable=SC2086
exec "$JAVA_BIN" --enable-preview $JAVA_OPTS \
  -jar "$JAR" \
  --spring.profiles.active="$PROFILE"
