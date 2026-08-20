#!/bin/bash
# SessionStart hook for Claude Code on the web (see the build-environment skill,
# ".claude/skills/build-environment"). Web-only: no-op on local machines.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# The sandbox has no locale configured; a bare JVM defaults to ASCII and
# GitService throws on non-ASCII repo paths.
echo 'export LANG=C.utf8' >> "$CLAUDE_ENV_FILE"

# The sandbox's JAVA_HOME points at a JDK 21, but the build targets Java 25 —
# a JAR run through run/run.sh (which honors JAVA_HOME) would die with
# UnsupportedClassVersionError. The default `java` on PATH is already 25;
# align JAVA_HOME with it.
if [ -d /usr/lib/jvm/java-25-openjdk-amd64 ]; then
  echo 'export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64' >> "$CLAUDE_ENV_FILE"
fi

# Warm the caches: the first ./gradlew run downloads the wrapper distribution,
# then this compiles backend main+test classes so `:backend:test` /
# `spotlessCheck` start fast. A JDK 25 is installed and is the sandbox default,
# so no init script is needed and the configuration cache stays on. Idempotent:
# incremental no-op on a warm cache. Best-effort: a warm-up that cannot run
# must not take the session down with it — the checks themselves still work.
if ! ./gradlew :backend:testClasses --quiet; then
  echo "session-start: backend warm-up failed; './run/test.sh unit' will rebuild" >&2
fi
