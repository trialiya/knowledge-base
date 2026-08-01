#!/usr/bin/env node
/**
 * Boots the backend jar (H2 profile, no real AI backend needed) and drives it with
 * Playwright's pre-installed Chromium to confirm the SPA actually renders. This is
 * the canonical example for the frontend-visual-check skill (.claude/skills/) — see
 * there for the full explanation (the two locale gotchas, auth, why the jar route).
 *
 * By default the app is seeded with db/sample-data.sql (see .claude/rules/
 * backend-data.md) so the screenshot shows real chat/document content instead of an
 * empty knowledge base — pass --no-seed to skip that and check bare-schema startup
 * instead. Seeding always targets a disposable local-db/h2-smoke file (deleted and
 * recreated on every run) via an env var override of spring.datasource.url with
 * AUTO_SERVER=TRUE, so a second, short-lived JVM (org.h2.tools.RunScript) can load
 * the SQL into the same file while the app is running — your real local-db/h2 (used
 * by 'Быстрый старт с H2') is never touched.
 *
 * The jar is built by the script itself, through `run/test.sh jar` — the same
 * wrapper every other check goes through, so the Gradle to use, the Java 21
 * fallback and GRADLE/KB_JAVA21 are decided there and not repeated here. Pass
 * --no-build to run against the jar already in backend/build/libs.
 *
 * Two unrelated things are both called "locale" here, and only one of them is
 * fixed by this script on its own:
 *   - The JVM's system locale (LANG/LC_ALL below) — always forced to C.utf8,
 *     because a bare JVM otherwise defaults to ASCII and GitService throws on
 *     the non-ASCII repo paths under docs/. Not configurable, not optional.
 *   - The browser's UI language — i18next-browser-languagedetector reads
 *     navigator.language when nothing is cached in localStorage ('kb-lang'),
 *     and this sandbox's Chromium reports 'en-US' with no locale set on the
 *     context, so screenshots come out in English even though the app's
 *     fallback and primary audience are Russian (see i18n/index.js). Fixed
 *     explicitly below via --locale=<code>, default 'ru' — do not rely on the
 *     browser's ambient default for this one.
 *
 * Usage:
 *   ./run/test.sh smoke                 # the canonical form; delegates to this script
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js \
 *     [screenshot.png] [--no-seed] [--no-build] [--locale=ru|en]
 *
 * Chromium and Playwright are pre-installed in the sandbox; do not run
 * `playwright install`.
 */
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const TEST_SH = path.join(ROOT, 'run/test.sh');
const JAR = path.join(ROOT, 'backend/build/libs/backend-1.0-SNAPSHOT.jar');
const SAMPLE_DATA = path.join(ROOT, 'backend/src/test/resources/db/sample-data.sql');
const SMOKE_DB = path.join(ROOT, 'local-db/h2-smoke'); // disposable — never local-db/h2
const BASE_URL = 'http://localhost:8080';
const AUTH = { username: 'admin', password: 'admin' };

const args = process.argv.slice(2);
const seed = !args.includes('--no-seed');
const build = !args.includes('--no-build');
const screenshotPath =
  args.find((a) => !a.startsWith('--')) || path.join(ROOT, 'playwright-smoke.png');
// The app's own fallbackLng (i18n/index.js) and the primary audience for this
// product (docs/ is Russian) — not the browser's ambient navigator.language,
// which this sandbox reports as 'en-US' regardless of what the app expects.
const localeArg = args.find((a) => a.startsWith('--locale='));
const locale = localeArg ? localeArg.slice('--locale='.length) : 'ru';

// Everything the build needs to know (system Gradle vs ./gradlew, the Java 21
// fallback) is test.sh's job; 'jar' is bootJar without the frontend tests.
// './run/test.sh smoke' does not pre-build — it lands here, so both entry points
// build exactly once, the same way.
function buildJar() {
  console.log('→ ./run/test.sh jar');
  const built = spawnSync(TEST_SH, ['jar'], { cwd: ROOT, stdio: 'inherit' });
  if (built.error) throw built.error;
  if (built.status !== 0) {
    throw new Error(`./run/test.sh jar failed (exit ${built.status ?? built.signal})`);
  }
}

function findH2Jar() {
  const base = path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1/com.h2database/h2');
  if (!fs.existsSync(base)) return null;
  for (const version of fs.readdirSync(base)) {
    for (const hash of fs.readdirSync(path.join(base, version))) {
      const dir = path.join(base, version, hash);
      const jar = fs.readdirSync(dir).find((f) => /^h2-.*\.jar$/.test(f) && !f.includes('sources'));
      if (jar) return path.join(dir, jar);
    }
  }
  return null;
}

function waitForHealth(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = async () => {
      try {
        const res = await fetch(`${BASE_URL}/actuator/health`);
        if (res.ok) return resolve();
      } catch {
        // backend not listening yet
      }
      if (Date.now() > deadline) {
        return reject(new Error('backend did not become healthy in time'));
      }
      setTimeout(tick, 1000);
    };
    tick();
  });
}

async function main() {
  if (build) {
    buildJar();
  } else if (!fs.existsSync(JAR)) {
    throw new Error(`--no-build given, but ${path.relative(ROOT, JAR)} does not exist yet.`);
  }

  // local-db/ is gitignored, so on a fresh clone it does not exist yet — and H2
  // does not create the parent directory of its database file. Needed for both
  // modes: the seeded run writes local-db/h2-smoke*, and --no-seed falls back to
  // application-h2.yaml's local-db/h2.
  fs.mkdirSync(path.dirname(SMOKE_DB), { recursive: true });

  for (const f of fs.readdirSync(path.dirname(SMOKE_DB)).filter((f) => f.startsWith('h2-smoke'))) {
    fs.rmSync(path.join(path.dirname(SMOKE_DB), f));
  }

  const datasourceUrl = seed
    ? `jdbc:h2:${SMOKE_DB};MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE`
    : undefined; // undefined -> app falls back to application-h2.yaml's local-db/h2

  const backend = spawn('java', ['--enable-preview', '-jar', JAR], {
    cwd: ROOT,
    env: {
      ...process.env,
      // glibc's built-in UTF-8 locale — without it the JVM defaults to ASCII and
      // GitService throws on non-ASCII repo paths (docs/проект/*.md).
      LANG: 'C.utf8',
      LC_ALL: 'C.utf8',
      SPRING_PROFILES_ACTIVE: 'h2',
      ...(datasourceUrl ? { SPRING_DATASOURCE_URL: datasourceUrl } : {}),
      AI_BASE_URL: process.env.AI_BASE_URL || 'http://localhost:9999/v1',
      AI_API_KEY: process.env.AI_API_KEY || 'dummy',
      AI_MODEL: process.env.AI_MODEL || 'dummy-model',
      PROJECT_PATH: '.',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  backend.stdout.on('data', (d) => process.stdout.write(`[backend] ${d}`));
  backend.stderr.on('data', (d) => process.stderr.write(`[backend] ${d}`));

  try {
    await waitForHealth();

    if (seed) {
      const h2Jar = findH2Jar();
      if (!h2Jar) {
        console.warn('h2 jar not found in ~/.gradle cache — skipping seed, continuing bare.');
      } else {
        const run = spawnSync(
          'java',
          [
            '-cp',
            h2Jar,
            'org.h2.tools.RunScript',
            '-url',
            datasourceUrl,
            '-user',
            'knowledgebase',
            '-password',
            'knowledgebase',
            '-script',
            SAMPLE_DATA,
          ],
          { stdio: 'inherit' },
        );
        if (run.status !== 0) {
          throw new Error(`RunScript failed loading ${SAMPLE_DATA} (exit ${run.status})`);
        }
        console.log(`Seeded from ${path.relative(ROOT, SAMPLE_DATA)}`);
      }
    }

    const browser = await chromium.launch({
      executablePath: '/opt/pw-browsers/chromium', // stable symlink to the versioned binary
      args: ['--no-sandbox'],
    });
    try {
      // locale drives navigator.language, which i18next-browser-languagedetector
      // reads before falling back to i18n/index.js's own fallbackLng — without
      // this the sandbox's Chromium renders the app in English (see the header
      // comment on the two locale gotchas).
      const context = await browser.newContext({ httpCredentials: AUTH, locale });
      const page = await context.newPage();
      await page.goto(BASE_URL);
      await page.waitForSelector('#root > *', { timeout: 15000 }); // React mounted
      if (seed) {
        // Both the sidebar's chat list (GET /api/chats) and the active chat's
        // messages (GET /{id}/messages) are fetched async after mount — #root
        // having children only proves the shell rendered, not that the seeded
        // chat and its messages have actually shown up yet. `.ws-item` is the
        // shared left-panel row (common/sidePanel.css) — here, a chat.
        await page.waitForSelector('.ws-item', { timeout: 15000 });
        await page.waitForSelector('.message', { timeout: 15000 });
      }
      await page.screenshot({ path: screenshotPath, fullPage: true });
      console.log(`Screenshot saved to ${screenshotPath}`);
    } finally {
      await browser.close();
    }
  } finally {
    backend.kill('SIGTERM');
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
