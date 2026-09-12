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
#                  only when it has to: no kb.jar, one built for another
#                  profile, or one another command has rebuilt since.  A repeat
#                  of the same profile starts straight away, and a source change
#                  is rebuilt the way run.sh expects it to be — by hand, or here
#                  with KB_BUILD=1
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-h2}"

JAR="$SCRIPT_DIR/../backend/build/libs/kb.jar"
# Which profile the JAR standing there was built for.  Nothing in the JAR's name
# says it, and the build that would answer the question is the one being avoided
# here, so the script writes down what it built.  Under build/, so `clean` takes
# it along; rewritten even when Gradle had nothing to do, which keeps it newer
# than the JAR and leaves a JAR rebuilt by anything else (a plain `test.sh jar`,
# with no generated definitions in it at all) with no profile to its name.
STAMP="$JAR.aot-profile"

BUILT_FOR=""
if [ -f "$JAR" ] && [ -f "$STAMP" ] && [ ! "$JAR" -nt "$STAMP" ]; then
  BUILT_FOR="$(cat "$STAMP")"
fi

case "${KB_BUILD:-}" in
  0) build=no ;;
  '') if [ "$BUILT_FOR" = "$PROFILE" ]; then build=no; else build=yes; fi ;;
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
  printf '%s\n' "$PROFILE" > "$STAMP"
  echo ""
fi

# Nothing downstream checks that the JAR holds the profile announced here:
# -Dspring.aot.enabled=true reads whatever generated definitions the JAR holds,
# be they another profile's or none.  A start that skipped the build is the one
# that can be wrong about it.
if [ "$build" = no ] && [ -n "$BUILT_FOR" ] && [ "$BUILT_FOR" != "$PROFILE" ]; then
  echo "ERROR: the JAR is built for profile '$BUILT_FOR', not '$PROFILE' —" >&2
  echo "  starting it would raise the other profile's beans.  Drop KB_BUILD=0." >&2
  exit 1
fi

if [ "$build" = no ] && [ -z "$BUILT_FOR" ]; then
  echo "Spring AOT: on — but the profile the JAR was built for is unknown:" >&2
  echo "  ${STAMP##*/} is missing or older than the JAR, so this script did not" >&2
  echo "  build it and it may hold no generated definitions at all." >&2
  echo "  KB_BUILD=1 builds it for '$PROFILE'." >&2
else
  echo "Spring AOT: on — profile '$PROFILE' is baked into the JAR"
  if [ "$build" = no ]; then
    echo "  (built earlier for this profile — KB_BUILD=1 to build it again)"
  fi
fi

# AotDetector reads this before there is a context to read Spring properties
# from, so it has to be a system property and cannot ride --spring.* on the
# command line.  JDK_JAVA_OPTIONS reaches both JVMs run.sh starts (the training
# run and the application) without this script restating run.sh's own
# JAVA_OPTS default.
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -Dspring.aot.enabled=true"
exec "$SCRIPT_DIR/run.sh" "$PROFILE"
