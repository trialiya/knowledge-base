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
#   JAVA_OPTS      as in run.sh
#   KB_AOT         0 disables the JVM AOT cache (Spring AOT stays on)
#   KB_AOT_CACHE   as in run.sh.  The cache is shared with it and retrained
#                  whenever the JAR is rebuilt, which a switch between the two
#                  scripts always implies
#   KB_BUILD       0 skips the build and starts whatever kb.jar is already
#                  built — for a second run with no source change
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-h2}"

# The build goes through test.sh rather than ./gradlew: which Gradle to use and
# the Java 21 fallback are decided there, and scripts/playwright-smoke.js
# already builds its JAR the same way.  KB_NATIVE is what turns the AOT half of
# backend/build.gradle on; nativeCompile is not run, so no GraalVM is needed.
if [ "${KB_BUILD:-1}" != "0" ]; then
  echo "Building the AOT JAR for profile '$PROFILE'..."
  echo ""
  KB_NATIVE=1 "$SCRIPT_DIR/test.sh" jar -- -Pkb.aot.profile="$PROFILE"
  echo ""
fi

echo "Spring AOT: on — profile '$PROFILE' is baked into the JAR"

# AotDetector reads this before there is a context to read Spring properties
# from, so it has to be a system property and cannot ride --spring.* on the
# command line.  JDK_JAVA_OPTIONS reaches both JVMs run.sh starts (the training
# run and the application) without this script restating run.sh's own
# JAVA_OPTS default.
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -Dspring.aot.enabled=true"
exec "$SCRIPT_DIR/run.sh" "$PROFILE"
