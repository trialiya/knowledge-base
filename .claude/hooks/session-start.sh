#!/bin/bash
# SessionStart hook for Claude Code on the web (see CLAUDE.md, "Building & testing
# in the Claude Code web sandbox"). Web-only: no-op on local machines.
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
# `gradle :backend:test` / `spotlessCheck` start fast. Only JDK 21 is
# available, hence the init script (see CLAUDE.md); --no-configuration-cache
# is required with it. Idempotent: incremental no-op on a warm cache.
/opt/gradle/bin/gradle :backend:testClasses \
  --init-script gradle/java21.gradle --no-configuration-cache --quiet
