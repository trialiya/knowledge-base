#!/usr/bin/env bash
# Build the Knowledge Base backend through Spring AOT and run it (Linux, macOS).
#
# Usage:
#   ./run-spring-aot.sh [profile]     # h2 by default, as in run.sh
#
# Three steps in one: build the JAR with Spring AOT for <profile>, train the
# JVM's AOT cache, start the app.  Only the first is this script's own — the
# other two are run.sh doing what it always does, with one system property
# added.
#
# What Spring AOT is, next to the cache run.sh already uses.  The cache holds
# classes the JVM has loaded and linked; Spring AOT generates the bean
# definitions themselves at build time, so the container stops deriving them
# from annotations at every start.  Different work, so the two add up.
#
# What it costs: the JAR is built for ONE profile.  `@Profile` and
# `@ConditionalOnProperty` are resolved during the build and the profile is
# baked in, which is why the profile is an argument to the build here and not
# only to the start.  Both effects hang off -Dspring.aot.enabled=true: the very
# same kb.jar started by run.sh is an ordinary profile-independent JAR again,
# generated definitions ignored.  A native image is the case where the flag is
# always on — docs/проект/нативный-образ-graalvm.md.
#
# Environment:
#   JAVA_OPTS      as in run.sh -- options for the application.  Kept out of the
#                  build below, which runs a JVM of its own that has no use for
#                  them: an -agentlib for tracing belongs to the run
#   KB_AOT         0 disables the JVM AOT cache (Spring AOT stays on)
#   KB_AOT_CACHE   as in run.sh, and the same file.  run.sh retrains it when the
#                  JAR is newer, so a cache trained by the other script is reused
#                  as it is -- valid either way, only short of the classes this
#                  run reaches for and that one did not
#   KB_BUILD       0 never builds, 1 always does.  By default the build runs
#                  unless the JAR itself can show it is still the right one:
#                  built for this profile, from the commit checked out now, with
#                  no uncommitted changes on either side.  So a pull, a checkout
#                  or an edit rebuilds, and starting the same thing twice starts
#                  straight away.  Reading that proof needs `unzip`; without it
#                  the JAR cannot be asked anything and every start rebuilds
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-h2}"

JAR="$SCRIPT_DIR/../backend/build/libs/kb.jar"
REPO="$SCRIPT_DIR/.."

# What the JAR standing there was built from — asked of the JAR itself, because
# that is the only answer that cannot drift away from it.  The build writes both
# files (see backend/build.gradle): `build.aot.profile` is the profile whose bean
# definitions are baked in, and git.properties is the commit they were generated
# from.  Neither exists in a JAR built by anything else — a plain `test.sh jar`,
# with no generated definitions in it at all — and that reads as "unknown", which
# is exactly what it is.
jar_entry() {
  unzip -p "$JAR" "$1" 2> /dev/null || true
}

JAR_PROFILE=""
JAR_COMMIT=""
JAR_DIRTY=""
if [ -f "$JAR" ] && command -v unzip > /dev/null 2>&1; then
  JAR_PROFILE="$(jar_entry META-INF/build-info.properties | sed -n 's/^build\.aot\.profile=//p')"
  JAR_GIT="$(jar_entry BOOT-INF/classes/git.properties)"
  JAR_COMMIT="$(printf '%s\n' "$JAR_GIT" | sed -n 's/^git\.commit\.id=//p')"
  JAR_DIRTY="$(printf '%s\n' "$JAR_GIT" | sed -n 's/^git\.dirty=//p')"
fi

# Whether the sources still are the ones in the JAR.  This is what makes `git
# pull` rebuild: the JAR remembers its commit, and a checkout that moves HEAD
# away from it is visible here.  Uncommitted work on either side answers "no" —
# a commit id says nothing about edits that were never committed, and the JAR
# cannot be vouched for then.  Rebuilding is the safe answer, and a rebuild that
# turns out to change nothing costs one warm Gradle run.
CURRENT=""
if [ -z "$(git -C "$REPO" status --porcelain 2> /dev/null)" ]; then
  CURRENT="$(git -C "$REPO" rev-parse HEAD 2> /dev/null || true)"
fi
same_sources=no
if [ -n "$CURRENT" ] && [ "$CURRENT" = "$JAR_COMMIT" ] && [ "$JAR_DIRTY" = false ]; then
  same_sources=yes
fi

case "${KB_BUILD:-}" in
  0) build=no ;;
  '') if [ "$JAR_PROFILE" = "$PROFILE" ] && [ "$same_sources" = yes ]; then build=no; else build=yes; fi ;;
  *) build=yes ;;
esac

# The build goes through test.sh rather than ./gradlew: which Gradle to use and
# the Java 21 fallback are decided there, and scripts/playwright-smoke.js
# already builds its JAR the same way.  KB_NATIVE is what turns the AOT half of
# backend/build.gradle on; nativeCompile is not run, so no GraalVM is needed.
if [ "$build" = yes ]; then
  echo "Building the AOT JAR for profile '$PROFILE'..."
  echo ""
  # JAVA_OPTS is emptied for this one command: gradlew hands it to the JVM it
  # starts itself, so an -agentlib meant for the application would attach to
  # Gradle instead -- and abort it outright on a JDK that has no such library.
  KB_NATIVE=1 JAVA_OPTS= "$SCRIPT_DIR/test.sh" jar -- -Pkb.aot.profile="$PROFILE"
  echo ""
fi

# Nothing downstream checks that the JAR holds the profile announced here:
# -Dspring.aot.enabled=true reads whatever generated definitions the JAR holds,
# be they another profile's or none.  A start that skipped the build is the one
# that can be wrong about it.
if [ "$build" = no ] && [ -n "$JAR_PROFILE" ] && [ "$JAR_PROFILE" != "$PROFILE" ]; then
  echo "ERROR: the JAR is built for profile '$JAR_PROFILE', not '$PROFILE' —" >&2
  echo "  starting it would raise the other profile's beans.  Drop KB_BUILD=0." >&2
  exit 1
fi

if [ "$build" = no ] && [ -z "$JAR_PROFILE" ]; then
  echo "Spring AOT: on — but the profile the JAR was built for is unknown:" >&2
  echo "  it carries no build.aot.profile, so this script did not build it and it" >&2
  echo "  may hold no generated definitions at all." >&2
  echo "  KB_BUILD=1 builds it for '$PROFILE'." >&2
else
  echo "Spring AOT: on — profile '$PROFILE' is baked into the JAR"
  # Only the default path can promise the JAR matches the sources -- it is the
  # promise that let it skip the build.  KB_BUILD=0 skips regardless, so there
  # the same line would be a claim nobody checked.
  if [ "$build" = no ] && [ "$same_sources" = yes ]; then
    echo "  (built earlier from the commit checked out now — KB_BUILD=1 to build it again)"
  elif [ "$build" = no ]; then
    echo "  WARNING: it was built from other sources — another commit, or a tree" >&2
    echo "  with uncommitted changes.  KB_BUILD=0 is what skipped the rebuild." >&2
  fi
fi

# AotDetector reads this before there is a context to read Spring properties
# from, so it has to be a system property and cannot ride --spring.* on the
# command line.  JDK_JAVA_OPTIONS reaches both JVMs run.sh starts (the training
# run and the application) without this script restating run.sh's own
# JAVA_OPTS default.
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -Dspring.aot.enabled=true"
exec "$SCRIPT_DIR/run.sh" "$PROFILE"
