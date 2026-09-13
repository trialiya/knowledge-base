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
#   KB_BUILD       0 skips the build step entirely -- the JAR standing there is
#                  started as it is, and the profile it was built for is read out
#                  of it (that read needs `unzip`).  Anything else, including the
#                  default, runs the build: Gradle is what knows whether the JAR
#                  is current, and a run with nothing to do costs about two
#                  seconds and leaves the JAR alone
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-h2}"

JAR="$SCRIPT_DIR/../backend/build/libs/kb.jar"

# Which profile the JAR standing there was built for, read out of the JAR itself:
# `build.aot.profile` in META-INF/build-info.properties, written only under
# KB_NATIVE (see backend/build.gradle).  Only KB_BUILD=0 needs the answer -- every
# other path gets it from the build it is about to run.
JAR_PROFILE=""
if [ -f "$JAR" ] && command -v unzip > /dev/null 2>&1; then
  JAR_PROFILE="$( (unzip -p "$JAR" META-INF/build-info.properties 2> /dev/null || true) \
    | sed -n 's/^build\.aot\.profile=//p')"
fi

# Whether the JAR is still current is Gradle's question, not this script's.  It was
# tried the other way once -- compare the JAR's own commit with HEAD and demand a
# clean tree -- and the tree is never clean: run/application.yaml is tracked and
# carries your own keys, so the check said "stale" every time and every start
# rebuilt from scratch.  Gradle knows what the real inputs are, and a run with
# nothing to do takes about two seconds and leaves the JAR untouched -- which is
# what keeps run.sh's AOT cache valid, and why the build writes no build time into
# the JAR by default (backend/build.gradle).
case "${KB_BUILD:-}" in
  0) build=no ;;
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
  # KB_BUILD=0 skips the build without asking anything about the sources, so the
  # JAR may be older than the working tree.  Nothing here can tell -- that is the
  # deal with KB_BUILD=0 -- and saying so is better than staying quiet.
  if [ "$build" = no ]; then
    echo "  (KB_BUILD=0: the build was skipped, so the JAR may be older than the sources)"
  fi
fi

# AotDetector reads this before there is a context to read Spring properties
# from, so it has to be a system property and cannot ride --spring.* on the
# command line.  JDK_JAVA_OPTIONS reaches both JVMs run.sh starts (the training
# run and the application) without this script restating run.sh's own
# JAVA_OPTS default.
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -Dspring.aot.enabled=true"
exec "$SCRIPT_DIR/run.sh" "$PROFILE"
