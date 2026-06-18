# CLAUDE.md

Guidance for Claude Code (and contributors) working in this repository.

## Project overview

Knowledge Base — an AI assistant for working with documentation and analyzing a
codebase. Spring Boot backend + React frontend, built as a single Gradle
multi-project (`:backend`, `:frontend`).

- **Backend:** Java 25, Spring Boot 3.5, Spring AI, PostgreSQL 17 + pgvector / H2
- **Frontend:** React 19 (react-scripts), bundled into the backend JAR
- **Build:** Gradle 9.5.1 (wrapper), dependency locking enabled
- Full docs (Russian) live under `docs/` — see `docs/проект/разработка-и-контрибьюция.md`.

## Common commands

```bash
./gradlew build                 # Full build (frontend → bundled into backend JAR)
./gradlew :backend:bootRun      # Run backend (dev) on :8080
./gradlew :frontend:yarnServe   # Frontend dev server on :3000 (proxies API to :8080)
./gradlew :backend:bootJar      # Executable JAR (frontend auto-bundled)
./gradlew spotlessApply         # Format backend (Google Java Format, AOSP)
./gradlew spotlessCheck         # Verify formatting
```

## Testing

### Default — Java 25

The backend toolchain targets **Java 25** (`backend/build.gradle`:
`languageVersion = JavaLanguageVersion.of(25)`). With a JDK 25 available, tests
run with no special flags:

```bash
./gradlew :backend:test                     # all backend tests (JUnit 5)
./gradlew :backend:test --tests "io.github.trialiya.kb.i18n.ToolTranslationsTest"
./gradlew :frontend:yarnTest                # frontend tests (Jest / react-scripts)
./gradlew check                             # tests + spotless across modules
```

> Integration tests (`*IT`) use Testcontainers and need Docker running. Pure unit
> tests (`*Test`) do not.

### Fallback — Java 21 with preview features

Some environments (certain CI runners, the Claude Code web sandbox) only ship
**JDK 21**, so the Java 25 toolchain can't be resolved and the build fails with:

```
Cannot find a Java installation ... matching: {languageVersion=25 ...}
```

The code itself compiles on Java 21, but a test uses an API that is a **preview
feature in Java 21** and was finalized in Java 22–25 — unnamed variables (`_`) in
`ToolTranslationsTest`. So on JDK 21 you must both retarget the toolchain to 21
and enable preview features.

A ready-made Gradle init script does this — `gradle/java21.gradle`:

```bash
# Run any backend test task on JDK 21 + preview features
./gradlew :backend:test \
    --init-script gradle/java21.gradle \
    --no-configuration-cache

# A single test:
./gradlew :backend:test \
    --tests "io.github.trialiya.kb.i18n.ToolTranslationsTest" \
    --init-script gradle/java21.gradle \
    --no-configuration-cache

# Compile only:
./gradlew :backend:compileTestJava \
    --init-script gradle/java21.gradle \
    --no-configuration-cache
```

What the script does (retarget + `--enable-preview` on compile and test JVMs):

```groovy
options.release = 21
options.compilerArgs += '--enable-preview'   // JavaCompile
jvmArgs += '--enable-preview'                // Test / JavaExec
javaCompiler / javaLauncher → JavaLanguageVersion.of(21)
```

Caveats for the Java 21 path:
- **`--no-configuration-cache` is required** — the per-task toolchain override is
  not serializable into Gradle's configuration cache (enabled by default in
  `gradle.properties`).
- The override is applied in `afterEvaluate` so it takes precedence over the
  Java 25 toolchain declared in `backend/build.gradle`.
- This is a local/CI workaround only. **Do not** change the toolchain in
  `backend/build.gradle` to 21 — production targets Java 25 (virtual threads etc.).
- Integration tests still need Docker regardless of the JDK.

## Before opening a PR

1. `./gradlew spotlessCheck`
2. `./gradlew :backend:test` (add `--init-script gradle/java21.gradle --no-configuration-cache` on JDK 21)
3. `./gradlew build`

## Conventions

- Backend formatting is enforced by Spotless (Google Java Format, AOSP style,
  sorted imports, no unused imports). Run `./gradlew spotlessApply` before committing.
- Frontend Spotless (Prettier) needs a local Node.js; the frontend build itself
  downloads Node/Yarn automatically via the Gradle node plugin.
- Dependency locking is on — after changing dependencies, regenerate locks:
  `./gradlew resolveAndLockAll --write-locks`.
