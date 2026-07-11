# CLAUDE.md

Guidance for Claude Code (and contributors) working in this repository.

## Project overview

Knowledge Base — AI assistant for documentation and codebase analysis. Single
Gradle multi-project: Spring Boot backend (`:backend`, Java 25, Spring AI,
PostgreSQL 17 + pgvector / H2) + React 19 frontend (`:frontend`), bundled into
the backend JAR. Full docs (Russian) under `docs/`.

## Common commands

```bash
./gradlew build                 # Full build (frontend bundled into backend JAR)
./gradlew :backend:bootRun      # Run backend (dev) on :8080
./gradlew :frontend:yarnServe   # Frontend dev server on :3000 (proxies to :8080)
./gradlew :backend:test         # Backend tests (JUnit 5)
./gradlew :frontend:yarnTest    # Frontend tests (Jest)
./gradlew spotlessApply         # Format backend (Google Java Format, AOSP)
```

`*IT` tests use Testcontainers and need Docker; `*Test` (unit) tests don't.

## Testing on JDK 21 (no JDK 25 available)

The backend toolchain targets Java 25. On environments with only JDK 21 (some CI
runners, the web sandbox) the build fails to resolve the toolchain. Apply the
`gradle/java21.gradle` init script to retarget to 21 and enable preview features
(`ToolTranslationsTest` uses unnamed variables `_`, a Java 21 preview finalized
in 22–25):

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required. This is a local/CI workaround only — keep
`backend/build.gradle` on Java 25.

## Running tests in the Claude Code web sandbox

No Docker → `*IT` tests always fail. Run unit tests only:

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache --tests "*Test"
```

IT failures are infrastructure-only. Verify them locally or in CI before a PR.

## Visually validating the frontend in the web sandbox (Playwright)

`frontend/` has no `playwright` dependency and there's no `chromium-cli`, but
a global install and a pre-fetched Chromium are already on the box (see
`ls /opt/pw-browsers` for the exact revision) — don't run `playwright install`.
Ad-hoc scripts need the global root on `NODE_PATH`:

```bash
NODE_PATH=/opt/node22/lib/node_modules node script.js
```

```js
const { chromium } = require('playwright');
const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-<rev>/chrome-linux/chrome',
  args: ['--no-sandbox'],
});
```

`yarn start` crashes here (`allowedHosts[0] should be a non-empty string` —
CRA's host-check reacts to the `proxy` field in `package.json`). Skip the dev
server: build once and run the jar directly, which serves the bundled
frontend from Spring Boot on `:8080`.

```bash
./gradlew :backend:bootJar -x :frontend:yarnTest \
  --init-script gradle/java21.gradle --no-configuration-cache

SPRING_PROFILES_ACTIVE=h2 AI_BASE_URL=http://localhost:9999/v1 AI_API_KEY=dummy \
AI_MODEL=dummy-model PROJECT_PATH=. \
java --enable-preview -jar backend/build/libs/backend-1.0-SNAPSHOT.jar
```

Use `java -jar`, not `./gradlew :backend:bootRun` — that task doesn't depend
on `:frontend:copyFrontend`, so running both together in one `./gradlew`
invocation trips Gradle's implicit-dependency validation. `--enable-preview`
is required: classes are compiled with the Java 21 preview features
`java21.gradle` enables (unnamed variables `_`). Dummy `AI_*` values are
enough for a UI-only smoke test — no real key needed. Auth is HTTP Basic
`admin`/`admin` (`kb.security.*`); pass it via Playwright's `httpCredentials`.
Poll the port instead of sleeping, and check `page.on('console', …)` /
`page.on('pageerror', …)` before trusting a screenshot.

## Before a PR

`./gradlew spotlessCheck` · `./gradlew :backend:test` · `./gradlew build`
(add `--init-script gradle/java21.gradle --no-configuration-cache` on JDK 21).
Dependency locking is on — after changing deps run
`./gradlew resolveAndLockAll --write-locks`.
