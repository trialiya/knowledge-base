#!/usr/bin/env bash
# Run the Knowledge Base checks on Linux or macOS.
#
# One entry point for every suite, so the awkward parts (system Gradle in the
# web sandbox, the JDK 21 init script, starting dockerd for Testcontainers,
# CI=true for Jest) are decided here instead of being retyped every session.
#
# Usage:
#   ./test.sh [suite ...]
#
# Suites:
#   unit      backend unit tests (*Test) — no Docker needed
#   it        backend integration tests (*IT) — needs Docker, started if absent
#   back      all backend tests (unit + IT)
#   front     frontend Jest tests
#   format    spotlessCheck (Google Java Format, AOSP)
#   build     full build (frontend bundled into the backend JAR)
#   smoke     build the JAR and drive the UI with Chromium (scripts/playwright-smoke.js);
#             scenarios and data for it live in frontend/tests/visual/cases.yaml
#   pre-pr    format + back + build — the list from CLAUDE.md, "Before a PR"
#
# No suite given → unit + front: the fast pair that needs neither Docker nor a JAR.
#
# Examples:
#   ./test.sh                 # quick check while working
#   ./test.sh front           # only Jest
#   ./test.sh pre-pr          # everything expected before a pull request
#   ./test.sh smoke           # look at the UI, not just at green tests
#
# Environment:
#   GRADLE      path to the Gradle to use (the web sandbox sets it; otherwise
#               ./gradlew is used, falling back to gradle on PATH)
#   KB_JAVA21   1 forces the Java 21 init script, 0 forbids it. Unset = decide by
#               the JDK actually found (the build targets Java 25, see
#               backend/build.gradle).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# The sandbox has no locale configured, and a bare JVM then defaults to ASCII —
# GitService throws on non-ASCII repo paths (docs/проект/*.md).
export LANG="${LANG:-C.utf8}"
export LC_ALL="${LC_ALL:-C.utf8}"

# ── Which Gradle ──────────────────────────────────────────────────────────────
# ./gradlew cannot download its distribution in the web sandbox, so a GRADLE
# pointing at a system install wins when it is set.
if [ -n "${GRADLE:-}" ]; then
  GRADLE_BIN="$GRADLE"
elif [ -x "$ROOT/gradlew" ]; then
  GRADLE_BIN="$ROOT/gradlew"
elif command -v gradle > /dev/null 2>&1; then
  GRADLE_BIN="gradle"
else
  echo "ERROR: no Gradle found — set GRADLE, or keep ./gradlew in the repo." >&2
  exit 1
fi

# ── Java 21 workaround ────────────────────────────────────────────────────────
# The toolchain targets Java 25; on a JDK older than that the build cannot
# resolve it, and gradle/java21.gradle retargets to 21 with preview features on.
# --no-configuration-cache is required with it (the toolchain override is not
# serializable).
java_major() {
  local java_bin="java"
  [ -n "${JAVA_HOME:-}" ] && java_bin="$JAVA_HOME/bin/java"
  "$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

GRADLE_ARGS=()
case "${KB_JAVA21:-auto}" in
  1) NEED_JAVA21=yes ;;
  0) NEED_JAVA21=no ;;
  *)
    major="$(java_major || true)"
    if [ -n "$major" ] && [ "$major" -lt 25 ]; then NEED_JAVA21=yes; else NEED_JAVA21=no; fi
    ;;
esac
if [ "$NEED_JAVA21" = yes ]; then
  GRADLE_ARGS+=(--init-script gradle/java21.gradle --no-configuration-cache)
fi

gradle_run() {
  echo "→ $GRADLE_BIN $*"
  "$GRADLE_BIN" "$@" "${GRADLE_ARGS[@]}"
}

# ── Docker for Testcontainers ─────────────────────────────────────────────────
# *IT tests need a daemon. In the sandbox there is none until we start it; on a
# developer machine Docker Desktop is normally already up, and then this is a
# no-op.
ensure_docker() {
  if docker ps > /dev/null 2>&1; then
    return 0
  fi
  if ! command -v dockerd > /dev/null 2>&1; then
    echo "ERROR: *IT tests need Docker, and no daemon is reachable." >&2
    echo "       Start Docker (Desktop or dockerd) and re-run, or use './test.sh unit'." >&2
    exit 1
  fi
  echo "→ starting dockerd (log: /tmp/dockerd.log)"
  sudo dockerd > /tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 60); do
    docker ps > /dev/null 2>&1 && return 0
    sleep 1
  done
  echo "ERROR: dockerd did not come up in 60s — see /tmp/dockerd.log" >&2
  exit 1
}

# ── Suites ────────────────────────────────────────────────────────────────────
run_unit()   { gradle_run :backend:test --tests "*Test"; }
run_it()     { ensure_docker; gradle_run :backend:test --tests "*IT"; }
run_back()   { ensure_docker; gradle_run :backend:test; }
run_front()  { CI=true gradle_run :frontend:yarnTest; }
run_format() { gradle_run spotlessCheck; }
run_build()  { gradle_run build; }

run_smoke() {
  # Jest is skipped here on purpose: the point is a running UI, and './test.sh
  # front' covers the tests.
  gradle_run :backend:bootJar -x :frontend:yarnTest
  local node_env=()
  [ -d /opt/node22/lib/node_modules ] && node_env=(env NODE_PATH=/opt/node22/lib/node_modules)
  echo "→ node scripts/playwright-smoke.js"
  "${node_env[@]}" node scripts/playwright-smoke.js
}

run_suite() {
  case "$1" in
    unit)   run_unit ;;
    it)     run_it ;;
    back)   run_back ;;
    front)  run_front ;;
    format) run_format ;;
    build)  run_build ;;
    smoke)  run_smoke ;;
    pre-pr) run_format; run_back; run_build ;;
    *)
      echo "ERROR: unknown suite '$1'." >&2
      echo "       Known: unit it back front format build smoke pre-pr" >&2
      exit 2
      ;;
  esac
}

SUITES=("$@")
[ ${#SUITES[@]} -eq 0 ] && SUITES=(unit front)

echo "Knowledge Base checks"
echo "  Gradle:  $GRADLE_BIN"
echo "  Java 21 workaround: $NEED_JAVA21"
echo "  Suites:  ${SUITES[*]}"
echo ""

for suite in "${SUITES[@]}"; do
  run_suite "$suite"
done

echo ""
echo "OK: ${SUITES[*]}"
