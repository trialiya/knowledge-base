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
# scripts/playwright-smoke.js starts the app through this script too, as
# './run.sh h2,playwright-smoke' (see application-playwright-smoke.yaml).
#
# Edit application.yaml (and, for a custom profile, the matching
# application-<profile>.yaml) before running.  The JVM is started from this
# directory so relative paths in application.yaml (e.g. kb.projects[0].path: ..)
# resolve against it.
#
# Environment:
#   JAVA_OPTS      JVM options for both the application and the AOT training run
#                  below (default -Xmx150m)
#   KB_AOT         0 disables the AOT cache entirely
#   KB_AOT_CACHE   path of the cache file, instead of local-db/aot/<profile>.aot
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

# ── AOT cache ─────────────────────────────────────────────────────────────────
# Starting from a cache of already loaded and linked classes (JDK 24+) is worth
# about 40% of this application's startup.  Writing one costs a training run:
# the application starts under -XX:AOTCacheOutput and Spring exits it the moment
# the context is refreshed, before the port is bound, so it can be done while an
# instance is running.
#
# The cache describes the classes of one JAR under one profile, and the JVM only
# rejects it when the JVM itself changed — a rebuilt JAR keeping the same name
# would be started from a stale cache.  Hence the timestamp check: a cache older
# than the JAR is retrained rather than used.
AOT_CACHE="${KB_AOT_CACHE:-$SCRIPT_DIR/../local-db/aot/$(printf '%s' "$PROFILE" | tr -c 'A-Za-z0-9._-' '-').aot}"
AOT_OPTS=()

if [ "${KB_AOT:-1}" != "0" ]; then
  if [ ! -f "$AOT_CACHE" ] || [ "$JAR" -nt "$AOT_CACHE" ]; then
    echo "Training the AOT cache (once per build): $AOT_CACHE"
    mkdir -p "$(dirname "$AOT_CACHE")"
    # The training run reads the same configuration as the real one, so its
    # failures are the real one's failures — reported by the start that follows
    # rather than here, where the log would be the only thing the user sees.
    # shellcheck disable=SC2086
    if ! (cd "$SCRIPT_DIR" && "$JAVA_BIN" --enable-preview $JAVA_OPTS \
        -XX:AOTCacheOutput="$AOT_CACHE" \
        -Dspring.context.exit=onRefresh \
        -jar "$JAR" \
        --spring.profiles.active="$PROFILE" > "$AOT_CACHE.log" 2>&1); then
      echo "  ...failed, starting without a cache (log: $AOT_CACHE.log)" >&2
      rm -f "$AOT_CACHE"
    fi
  fi
  # An unreadable cache only costs the JVM a warning and a normal startup, but
  # passing a path that is not there says "cache" in the banner below and means
  # nothing of the sort.
  if [ -f "$AOT_CACHE" ]; then
    AOT_OPTS=(-XX:AOTCache="$AOT_CACHE")
  fi
fi

echo "Starting Knowledge Base..."
echo "  Profile: $PROFILE"
echo "  Config:  $SCRIPT_DIR/application.yaml + application-$PROFILE.yaml"
echo "  JAR:     $JAR"
echo "  JAVA:    $JAVA_BIN"
echo "  AOT:     ${AOT_OPTS[0]:-off}"
echo ""

cd "$SCRIPT_DIR"

# ${AOT_OPTS[@]+...} keeps an empty array from tripping `set -u` on bash 3.2,
# which is still what macOS ships as /bin/bash.
# shellcheck disable=SC2086
exec "$JAVA_BIN" --enable-preview $JAVA_OPTS ${AOT_OPTS[@]+"${AOT_OPTS[@]}"} \
  -jar "$JAR" \
  --spring.profiles.active="$PROFILE"
