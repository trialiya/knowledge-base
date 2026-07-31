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
# ./gradlew can't download the distribution here — use the system Gradle.
echo 'export GRADLE=/opt/gradle/bin/gradle' >> "$CLAUDE_ENV_FILE"

# Warm the dependency cache and compile backend main+test classes so
# `gradle :backend:test` / `spotlessCheck` start fast. The image ships a JDK 25
# next to the JDK 21 that JAVA_HOME points at, and Gradle auto-detects it for
# the toolchain — so this compiles against the same Java 25 run/test.sh will
# use, with no init script and the configuration cache left on. Idempotent:
# incremental no-op on a warm cache. Best-effort: a warm-up that cannot run
# must not take the session down with it — the checks themselves still work.
if ! /opt/gradle/bin/gradle :backend:testClasses --quiet; then
  echo "session-start: backend warm-up failed; './run/test.sh unit' will rebuild" >&2
fi
