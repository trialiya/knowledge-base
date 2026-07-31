---
name: build-environment
description: Environment scaffolding for the Knowledge Base build — the Java 25 toolchain and its Java 21 fallback, the system Gradle in the Claude Code web sandbox, and Testcontainers image pulls. Read it when running Gradle by hand, or when a build fails on the Java toolchain, a blocked download, or a container image.
---

# Build environment

Scaffolding for two environments that will outlive their need: runners with no
JDK 25, and the Claude Code web sandbox. **`run/test.sh` already applies all of
it** — read on only when running Gradle by hand or when the wrapper itself
misbehaves. Delete a section here once its environment is gone.

## Java 25, with a Java 21 fallback

The build targets Java 25. Where no JDK 25 exists, `gradle/java21.gradle`
retargets the toolchain to 21 and enables the preview features
`ToolTranslationsTest` needs:

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required. `run/test.sh` makes that call itself —
force it with `KB_JAVA21=1` (always) or `KB_JAVA21=0` (never). Keep
`backend/build.gradle` on Java 25.

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
