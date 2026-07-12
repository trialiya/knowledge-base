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

## Building & testing in the Claude Code web sandbox

**`./gradlew` does not work here** (the gradle-9.6.1 download is blocked) —
use the system Gradle at `/opt/gradle/bin/gradle`.

**For `*IT` tests start `dockerd` first:**

```bash
sudo dockerd > /tmp/dockerd.log 2>&1 &
until docker ps > /dev/null 2>&1; do sleep 1; done
```

Then (init script needed because only JDK 21 is available, see above):

```bash
# Unit tests only (no Docker needed)
/opt/gradle/bin/gradle :backend:test --init-script gradle/java21.gradle --no-configuration-cache --tests "*Test"

# All backend tests incl. *IT (dockerd must be running)
/opt/gradle/bin/gradle :backend:test --init-script gradle/java21.gradle --no-configuration-cache

# Full build (frontend Node/yarn downloads work; Jest runs too)
/opt/gradle/bin/gradle build --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required with the init script. Maven Central,
plugins.gradle.org, nodejs.org and Docker Hub are all reachable; only the
Gradle distribution download is blocked.

## Visually validating the frontend in the web sandbox (Playwright)

Chromium is pre-installed and Playwright is global (no `playwright install`):

```bash
NODE_PATH=/opt/node22/lib/node_modules node script.js
```

```js
const { chromium } = require('playwright');
const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium', // stable symlink to the versioned binary
  args: ['--no-sandbox'],
});
```

Run the app like this, not `yarn start` (yarn's dev server doesn't work here):

```bash
/opt/gradle/bin/gradle :backend:bootJar -x :frontend:yarnTest \
  --init-script gradle/java21.gradle --no-configuration-cache

LANG=C.utf8 LC_ALL=C.utf8 \
SPRING_PROFILES_ACTIVE=h2 AI_BASE_URL=http://localhost:9999/v1 AI_API_KEY=dummy \
AI_MODEL=dummy-model PROJECT_PATH=. \
java --enable-preview -jar backend/build/libs/backend-1.0-SNAPSHOT.jar
```

`:backend:bootRun` also works with the same env vars plus the init script
(it injects `--enable-preview` into JavaExec tasks); the jar route above is
closer to prod and easier to background.

Auth: HTTP Basic `admin`/`admin`. Poll the port before driving with Playwright.

`LANG=C.utf8` matters: the sandbox has no locale configured, so the JVM defaults
to `sun.jnu.encoding=ANSI_X3.4-1968` (ASCII), and any code touching a non-ASCII
path (e.g. `GitService` reading `docs/проект/*.md`) throws "Malformed input or
input contains unmappable characters". `C.utf8` is glibc's built-in UTF-8 locale
— no `locale-gen` needed.

## Before a PR

`./gradlew spotlessCheck` · `./gradlew :backend:test` · `./gradlew build`
(add `--init-script gradle/java21.gradle --no-configuration-cache` on JDK 21;
in the web sandbox use `/opt/gradle/bin/gradle` and start `dockerd` first —
all three checks can run there, IT tests included).
Dependency locking is on — after changing deps run
`./gradlew resolveAndLockAll --write-locks`.
