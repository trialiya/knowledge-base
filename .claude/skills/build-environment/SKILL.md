---
name: build-environment
description: Environment scaffolding for the Knowledge Base build — the JDK 21 init script, the system Gradle in the Claude Code web sandbox, and Testcontainers image pulls. Read it when running Gradle by hand, or when a build fails on the Java toolchain, a blocked download, or a container image.
---

# Build environment

Scaffolding for two environments that will outlive their need: runners with no
JDK 25, and the Claude Code web sandbox. **`run/test.sh` already applies all of
it** — read on only when running Gradle by hand or when the wrapper itself
misbehaves. Delete a section here once its environment is gone.

## Testing on JDK 21 (no JDK 25 available)

The backend toolchain targets Java 25. Where only JDK 21 exists (some CI
runners, the web sandbox) the build fails to resolve the toolchain. Apply the
`gradle/java21.gradle` init script to retarget to 21 and enable preview features
— `ToolTranslationsTest` uses unnamed variables (`_`), a Java 21 preview
finalized in 22–25:

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required. This is a local/CI workaround only —
keep `backend/build.gradle` on Java 25.

`run/test.sh` applies it by itself: it reads the major version of the JDK it
would use and adds both flags below 25. Force the decision with `KB_JAVA21=1`
(always) or `KB_JAVA21=0` (never) when the heuristic guesses wrong — e.g. when
Gradle is pinned to a different JDK than the `java` on `PATH`.

## The Claude Code web sandbox

**`./gradlew` does not work here** — the gradle-9.6.1 download is blocked. Use
the system Gradle at `/opt/gradle/bin/gradle`.

A SessionStart hook (`.claude/hooks/session-start.sh`, web-only) already sets
`LANG=C.utf8`, exports `GRADLE=/opt/gradle/bin/gradle`, and pre-compiles backend
main + test classes with the JDK 21 init script — so the dependency cache is
warm and `spotlessCheck` / unit tests start fast with no extra setup.

`run/test.sh` needs nothing extra here: it picks up the hook's `GRADLE`, adds
the init script, and starts `dockerd` itself for the `*IT` suites. Maven
Central, plugins.gradle.org, nodejs.org and Docker Hub are all reachable; only
the Gradle distribution download is blocked.

## Testcontainers image caching

`*IT` suites pull `pgvector/pgvector:pg17` on first run, which can be slow or
fail transiently. If `./run/test.sh` hits a `ContainerFetchException` for it,
run `docker pull pgvector/pgvector:pg17` and retry — the image stays cached
locally after that.
