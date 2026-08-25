#!/usr/bin/env bash
# Run the Knowledge Base checks on Linux or macOS.
#
# One entry point for every suite, so the awkward parts (which Gradle to use,
# the Java 21 fallback, starting dockerd for Testcontainers) are decided here
# instead of being retyped every session.
#
# Usage:
#   ./test.sh [suite ...] [-- <extra gradle args>]
#
# Suites:
#   unit        backend unit tests (*Test) — no Docker needed
#   it          backend integration tests (*IT) — needs Docker, started if absent
#   back        all backend tests (unit + IT)
#   front       frontend tests (vitest) + eslint
#   format      spotlessCheck (Google Java Format, AOSP) — fails on a violation
#   formatApply spotlessApply — same rules, rewrites the files instead of failing
#   build       full build (frontend bundled into the backend JAR)
#   jar         just the runnable backend JAR (bootJar, frontend bundled, no tests)
#   clean       gradle clean — when something is stuck in the toolchain/spotless cache
#   smoke       drive the UI with Chromium (scripts/playwright-smoke.js); that script
#               builds the JAR through the 'jar' suite itself, so running it directly
#               behaves the same. Scenarios and data live in
#               frontend/tests/visual/cases.yaml
#   pre-pr      format + back + build — the gate before a pull request
#   ci          the same three with --console=plain (non-interactive logs). Note: the
#               GitHub workflows do not call this — they run ./gradlew per module.
#
# No suite given → unit + front: the fast pair that needs neither Docker nor a JAR.
#
# Everything after `--` is passed to Gradle as is, so narrowing down to a single
# test does not mean dropping out of the wrapper. A `--tests` of your own
# replaces the suite's default filter instead of widening it (Gradle ORs the
# patterns, so keeping both would run everything).
#
# Examples:
#   ./test.sh                 # quick check while working
#   ./test.sh front           # only the frontend checks
#   ./test.sh pre-pr          # everything expected before a pull request
#   ./test.sh smoke           # look at the UI, not just at green tests
#   ./test.sh unit -- --tests '*ToolTranslationsTest'      # one class
#   ./test.sh back -- --info                               # noisier output
#   ./test.sh clean build     # rebuild from scratch
#
# Environment:
#   GRADLE      path to a Gradle to use instead of ./gradlew (which is the
#               default, falling back to gradle on PATH)
#   KB_JAVA21   1 forces the Java 21 init script, 0 forbids it. Unset = decide by
#               whether a JDK 25 is installed anywhere Gradle looks for
#               toolchains (the build targets Java 25, see backend/build.gradle).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# The sandbox has no locale configured, and a bare JVM then defaults to ASCII —
# GitService throws on non-ASCII repo paths (docs/проект/*.md).
export LANG="${LANG:-C.utf8}"
export LC_ALL="${LC_ALL:-C.utf8}"

# ── Which Gradle ──────────────────────────────────────────────────────────────
# A GRADLE pointing at a system install wins when it is set — the escape hatch
# for environments where ./gradlew cannot download its distribution.
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

# ── Java 21 fallback ──────────────────────────────────────────────────────────
# The build targets a Java 25 toolchain (backend/build.gradle), which Gradle
# resolves by auto-detection: it is enough for a JDK 25 to be *installed*
# somewhere it scans — the daemon itself may keep running on an older JVM. So
# the question is not "which JDK runs Gradle" but "is a JDK 25 present at all";
# only when none is does gradle/java21.gradle step in and retarget to 21 with
# preview features on. --no-configuration-cache is required with it (the
# toolchain override is not serializable).
java_major() {
  local java_bin="java"
  [ -n "${JAVA_HOME:-}" ] && java_bin="$JAVA_HOME/bin/java"
  "$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

# Major version out of a JDK's own `release` file — reads an installation
# without paying to launch it. Silent for a directory that is not a JDK.
jdk_home_major() {
  [ -r "$1/release" ] || return 0
  sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$1/release" | head -1
}

have_jdk25() {
  local major home
  major="$(java_major || true)"
  [ -n "$major" ] && [ "$major" -ge 25 ] && return 0
  # The usual install locations, which are also the ones Gradle auto-detects.
  # A glob matching nothing stays literal and just fails the -r test above.
  for home in \
    /usr/lib/jvm/* \
    /usr/java/* \
    "${SDKMAN_DIR:-$HOME/.sdkman}"/candidates/java/* \
    "$HOME"/.asdf/installs/java/* \
    "$HOME"/.jenv/versions/* \
    /Library/Java/JavaVirtualMachines/*/Contents/Home \
    /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home; do
    major="$(jdk_home_major "$home")"
    [ -n "$major" ] && [ "$major" -ge 25 ] && return 0
  done
  return 1
}

GRADLE_ARGS=()
case "${KB_JAVA21:-auto}" in
  1) NEED_JAVA21=yes ;;
  0) NEED_JAVA21=no ;;
  *) if have_jdk25; then NEED_JAVA21=no; else NEED_JAVA21=yes; fi ;;
esac
if [ "$NEED_JAVA21" = yes ]; then
  GRADLE_ARGS+=(--init-script gradle/java21.gradle --no-configuration-cache)
fi

# Empty arrays are expanded through the ${arr[@]+"${arr[@]}"} guard everywhere
# below: macOS still ships bash 3.2, where a bare "${empty[@]}" under `set -u`
# aborts with "unbound variable". Both arrays are legitimately empty — no extra
# args, and JDK 25 needing no init script.
gradle_run() {
  echo "→ $GRADLE_BIN $*"
  "$GRADLE_BIN" "$@" ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} 2>&1 | sed \
    -e '/^Picked up JAVA_TOOL_OPTIONS/d' \
    -e '/^OpenJDK.*Sharing is only supported/d' \
    -e '/^WARNING: /d' \
    -e '/HikariPool.*Shutdown/d'
}

# ── Docker for Testcontainers ─────────────────────────────────────────────────
# *IT tests need a daemon. In the sandbox there is none until we start it; on a
# developer machine Docker Desktop is normally already up, and then this is a
# no-op.
ensure_docker() {
  if ! docker ps > /dev/null 2>&1; then
    if ! command -v dockerd > /dev/null 2>&1; then
      echo "ERROR: *IT tests need Docker, and no daemon is reachable." >&2
      echo "       Start Docker (Desktop or dockerd) and re-run, or use './test.sh unit'." >&2
      exit 1
    fi
    echo "→ starting dockerd (log: /tmp/dockerd.log)"
    sudo dockerd > /tmp/dockerd.log 2>&1 &
    for _ in $(seq 1 60); do
      docker ps > /dev/null 2>&1 && break
      sleep 1
    done
    if ! docker ps > /dev/null 2>&1; then
      echo "ERROR: dockerd did not come up in 60s — see /tmp/dockerd.log" >&2
      exit 1
    fi
  fi

  # `docker ps` only proves the API socket answers — a daemon that just came
  # up can still be finishing bridge-network/iptables setup underneath. Hit
  # that half-ready window and Testcontainers' first container attempt (Ryuk)
  # fails inside a static initializer that the JVM caches for the rest of the
  # run, so every *IT test then fails with NoClassDefFoundError even though
  # the same daemon is fine a few seconds later. Prove it can actually run a
  # container before handing off to Gradle.
  for _ in $(seq 1 30); do
    docker run --rm hello-world > /dev/null 2>&1 && return 0
    sleep 1
  done
  echo "ERROR: Docker answers 'docker ps' but would not run a container — see /tmp/dockerd.log" >&2
  exit 1
}

# ── Suites ────────────────────────────────────────────────────────────────────
# unit/it narrow :backend:test by name — but only while the caller has not
# supplied a --tests of their own (see OWN_FILTER below).
run_unit() {
  if [ "$OWN_FILTER" = yes ]; then
    gradle_run :backend:test --tests "*Test"
  else
    gradle_run :backend:test
  fi
}
run_it() {
  ensure_docker
  if [ "$OWN_FILTER" = yes ]; then
    gradle_run :backend:test --tests "*IT"
  else
    gradle_run :backend:test
  fi
}
run_back()   { ensure_docker; gradle_run :backend:test; }
run_front()  { gradle_run :frontend:yarnTest :frontend:yarnLint; }
run_format() { gradle_run spotlessCheck; }
run_format_apply() { gradle_run spotlessApply; }
run_build()  { gradle_run build; }
run_clean()  { gradle_run clean; }
# bootJar bundles the frontend without running vitest — the tests live on
# ':frontend:check', which './test.sh front' covers.
run_jar()    { gradle_run :backend:bootJar; }

run_smoke() {
  # No bootJar here — playwright-smoke.js invokes './test.sh jar' itself, so a
  # bare `node scripts/playwright-smoke.js` runs against just as fresh a JAR as
  # this suite does, and the build stays in one place.
  echo "→ node scripts/playwright-smoke.js"
  if [ -d /opt/node22/lib/node_modules ]; then
    env NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js
  else
    node scripts/playwright-smoke.js
  fi
}

run_suite() {
  case "$1" in
    unit)   run_unit ;;
    it)     run_it ;;
    back)   run_back ;;
    front)  run_front ;;
    format) run_format ;;
    formatApply) run_format_apply ;;
    build)  run_build ;;
    jar)    run_jar ;;
    clean)  run_clean ;;
    smoke)  run_smoke ;;
    pre-pr | ci) run_format; run_back; run_build ;;
    *)
      echo "ERROR: unknown suite '$1'." >&2
      echo "       Known: unit it back front format formatApply build jar clean smoke pre-pr ci" >&2
      exit 2
      ;;
  esac
}

# ── Arguments ─────────────────────────────────────────────────────────────────
# Suites up to `--`, everything after it goes to Gradle untouched.
SUITES=()
EXTRA_ARGS=()
seen_dashdash=no
for arg in "$@"; do
  if [ "$seen_dashdash" = yes ]; then
    EXTRA_ARGS+=("$arg")
  elif [ "$arg" = "--" ]; then
    seen_dashdash=yes
  else
    SUITES+=("$arg")
  fi
done

if [ ${#SUITES[@]} -eq 0 ]; then
  SUITES=(unit front)
fi

# Own --tests filter of unit/it, suppressed when the caller supplied one.
OWN_FILTER=yes
for arg in ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}; do
  if [ "$arg" = "--tests" ]; then
    OWN_FILTER=no
  fi
done

# A readable log matters more than progress bars on CI.
for suite in ${SUITES[@]+"${SUITES[@]}"}; do
  if [ "$suite" = "ci" ]; then
    GRADLE_ARGS+=(--console=plain)
  fi
done

echo "Knowledge Base checks"
echo "  Gradle:  $GRADLE_BIN"
echo "  Java 21 fallback: $NEED_JAVA21"
echo "  Suites:  ${SUITES[*]}"
if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
  echo "  Gradle args: ${EXTRA_ARGS[*]}"
fi
echo ""

for suite in "${SUITES[@]}"; do
  run_suite "$suite"
done

echo ""
echo "OK: ${SUITES[*]}"
