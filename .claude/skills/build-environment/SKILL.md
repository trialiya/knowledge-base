---
name: build-environment
description: Environment scaffolding for the Knowledge Base build — the Java 25 toolchain and its Java 21 fallback, the system Gradle in the Claude Code web sandbox, and Testcontainers image pulls. Read it when running Gradle by hand, or when a build fails on the Java toolchain, a blocked download, or a container image.
---

# Build environment

Scaffolding for two environments that will outlive their need: runners with no
JDK 25, and the Claude Code web sandbox. **`run/test.sh` already applies all of
it** — read on only when running Gradle by hand or when the wrapper itself
misbehaves. Delete a section here once its environment is gone.

## The Java 25 toolchain

The backend targets a Java 25 toolchain, and Gradle resolves it by
auto-detection — a JDK 25 only has to be **installed** somewhere Gradle scans
(`/usr/lib/jvm`, SDKMAN, jenv, asdf, the macOS and Homebrew locations). The
daemon itself may keep running on an older JVM; `JAVA_HOME` pointing at a JDK 21
is not a problem on its own.

**The web sandbox now ships a JDK 25** (`/usr/lib/jvm/java-25-openjdk-amd64`,
and `/usr/bin/java` resolves to it), so nothing special is needed here — plain
`gradle :backend:test` compiles at Java 25, configuration cache and all.

## The Java 21 fallback (no JDK 25 anywhere)

Where no JDK 25 exists at all, the build cannot resolve the toolchain. Apply the
`gradle/java21.gradle` init script to retarget to 21 and enable preview features
— `ToolTranslationsTest` uses unnamed variables (`_`), a Java 21 preview
finalized in 22–25:

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required. This is a local/CI fallback only — keep
`backend/build.gradle` on Java 25.

`run/test.sh` decides this by itself: it looks for an installed JDK 25 and adds
both flags only when it finds none. Force the decision with `KB_JAVA21=1`
(always) or `KB_JAVA21=0` (never) when that guess is wrong — e.g. when Gradle
runs with toolchain auto-detection disabled.

## The Claude Code web sandbox

**`./gradlew` does not work here** — the gradle-9.6.1 download is blocked. Use
the system Gradle at `/opt/gradle/bin/gradle`.

A SessionStart hook (`.claude/hooks/session-start.sh`, web-only) already sets
`LANG=C.utf8`, exports `GRADLE=/opt/gradle/bin/gradle`, and pre-compiles backend
main + test classes on the Java 25 toolchain — so the dependency cache is warm
and `spotlessCheck` / unit tests start fast with no extra setup.

`run/test.sh` needs nothing extra here: it picks up the hook's `GRADLE` and
starts `dockerd` itself for the `*IT` suites. Maven Central, plugins.gradle.org,
nodejs.org and Docker Hub are all reachable; only the Gradle distribution
download is blocked.

## Testcontainers image caching

`*IT` suites pull `pgvector/pgvector:pg17` on first run, which can be slow or
fail transiently. If `./run/test.sh` hits a `ContainerFetchException` for it,
run `docker pull pgvector/pgvector:pg17` and retry — the image stays cached
locally after that.
