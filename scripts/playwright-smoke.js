#!/usr/bin/env node
/**
 * Boots the app and drives it with Chromium to confirm the SPA actually renders.
 * The canonical example for the frontend-visual-check skill (.claude/skills/) —
 * the full explanation lives there.
 *
 * Building and launching both go through the usual wrappers, so nothing about the
 * Gradle to use or the JVM to start is decided here: `run/test.sh jar` builds
 * (--no-build reuses backend/build/libs), `run/run.sh h2,playwright-smoke` runs.
 * The profile (run/application-playwright-smoke.yaml) owns the disposable
 * local-db/h2-smoke datasource — your real local-db/h2 is never touched.
 *
 * By default the app is seeded with db/sample-data.sql (see .claude/rules/
 * backend-data.md), so the screenshot shows real content; --no-seed leaves the
 * same disposable database empty.
 *
 * Chromium and Playwright are pre-installed in the sandbox; do not run
 * `playwright install`.
 *
 * Usage:
 *   ./run/test.sh smoke                 # the canonical form; delegates to this script
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js \
 *     [screenshot.png] [--no-seed] [--no-build] [--locale=ru|en]
 */
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const TEST_SH = path.join(ROOT, 'run/test.sh');
const RUN_SH = path.join(ROOT, 'run/run.sh');
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
// Defaults to the app's own fallbackLng (i18n/index.js), not the browser's
// ambient navigator.language — see the browser.newContext() call below.
const localeArg = args.find((a) => a.startsWith('--locale='));
const locale = localeArg ? localeArg.slice('--locale='.length) : 'ru';

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
  // does not create the parent directory of its database file.
  fs.mkdirSync(path.dirname(SMOKE_DB), { recursive: true });

  for (const f of fs.readdirSync(path.dirname(SMOKE_DB)).filter((f) => f.startsWith('h2-smoke'))) {
    fs.rmSync(path.join(path.dirname(SMOKE_DB), f));
  }

  // The same file the profile opens as ../local-db/h2-smoke from run/ — H2
  // canonicalizes the path, and its AUTO_SERVER lets the seeding JVM below join
  // the running app on it.
  const datasourceUrl = `jdbc:h2:${SMOKE_DB};MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE`;

  const backend = spawn(RUN_SH, ['h2,playwright-smoke'], {
    cwd: ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
    // A run.sh AOT cache is keyed on the JAR, and this script rebuilds the JAR
    // by default — the cache would be retrained on every screenshot run and
    // used by none of them.
    env: { ...process.env, KB_AOT: '0' },
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
      // it the sandbox's Chromium ('en-US') renders the app in English. Set it
      // explicitly in any ad hoc script too; it will not happen on its own.
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
