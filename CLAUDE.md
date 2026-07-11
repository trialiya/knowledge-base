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

There's no `chromium-cli` here and `frontend/` doesn't depend on `playwright`,
but a global install and a pre-fetched Chromium are already on the box —
don't run `playwright install`:

```bash
npm ls -g --depth=0 | grep playwright   # global install, not under frontend/node_modules
ls /opt/pw-browsers                     # pre-fetched chromium-<rev>/chrome-linux/chrome
```

Ad-hoc scripts need the global root on `NODE_PATH` and must launch the
pre-fetched binary explicitly:

```bash
NODE_PATH=/opt/node22/lib/node_modules node script.js
```

```js
const { chromium } = require('playwright');
const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-<rev>/chrome-linux/chrome', // see `ls /opt/pw-browsers`
  args: ['--no-sandbox'],
});
```

**Serve the app on a single port instead of running `yarn start`.** The dev
server (`yarnServe`/`yarn start`) hits `Invalid options object ...
allowedHosts[0] should be a non-empty string` in this sandbox — CRA's
webpack-dev-server host-check reacts to the `proxy` field in
`frontend/package.json` and can't resolve a LAN host here. Rather than
fighting it, build once and let Spring Boot serve the bundled frontend:

```bash
./gradlew :backend:bootJar -x :frontend:yarnTest \
  --init-script gradle/java21.gradle --no-configuration-cache   # builds & copies frontend into build/resources/main/static

AI_BASE_URL=http://localhost:9999/v1 AI_API_KEY=dummy AI_MODEL=dummy-model \
AI_EMBED_MODEL=bge-m3 PROJECT_PATH=/tmp/kb-project DOCUMENTS_EXPORT_PATH=/tmp/kb-export \
./gradlew :backend:bootRun --args='--spring.profiles.active=h2' \
  --init-script gradle/java21.gradle --no-configuration-cache   # separate invocation — see below
```

Run these as **two separate `./gradlew` invocations**, not
`:backend:bootJar :backend:bootRun` in one command — `bootRun` doesn't
declare a dependency on `:frontend:copyFrontend` (only `bootJar` does, see
`backend/build.gradle`), and putting both tasks in one build graph can trip
Gradle's implicit-dependency validation since they touch the same
`build/resources/main` output. Two invocations sidestep it: the first
leaves the built frontend on disk, the second just serves it.

The `h2` profile still eagerly builds the OpenAI chat/embedding clients, so
the dummy values above for `AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL`/
`AI_EMBED_MODEL`/`PROJECT_PATH`/`DOCUMENTS_EXPORT_PATH` are required just to
reach a running app — no real key needed for a UI-only smoke test. Auth is
HTTP Basic, `admin`/`admin` by default (`kb.security.*`); pass it via
Playwright's `httpCredentials` context option. Poll the port instead of
sleeping, then drive with `page.goto`/`page.screenshot`, and check
`page.on('console', …)` / `page.on('pageerror', …)` before trusting a
screenshot — a page can render its shell while every fetch fails.

## Before a PR

`./gradlew spotlessCheck` · `./gradlew :backend:test` · `./gradlew build`
(add `--init-script gradle/java21.gradle --no-configuration-cache` on JDK 21).
Dependency locking is on — after changing deps run
`./gradlew resolveAndLockAll --write-locks`.
