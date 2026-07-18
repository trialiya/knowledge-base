#!/usr/bin/env node
/**
 * Boots the backend jar (H2 profile, no real AI backend needed) and drives it with
 * Playwright's pre-installed Chromium to confirm the SPA actually renders. This is
 * the canonical example for "Visually validating the frontend" in CLAUDE.md — see
 * there for the full explanation (locale gotcha, auth, why the jar route).
 *
 * By default the app is seeded with db/sample-data.sql (see CLAUDE.md, 'Тестовые
 * данные для H2') so the screenshot shows real chat/document content instead of an
 * empty knowledge base — pass --no-seed to skip that and check bare-schema startup
 * instead. Seeding always targets a disposable local-db/h2-smoke file (deleted and
 * recreated on every run) via an env var override of spring.datasource.url with
 * AUTO_SERVER=TRUE, so a second, short-lived JVM (org.h2.tools.RunScript) can load
 * the SQL into the same file while the app is running — your real local-db/h2 (used
 * by 'Быстрый старт с H2') is never touched.
 *
 * Prerequisites (build the jar first — this script does not):
 *   /opt/gradle/bin/gradle :backend:bootJar -x :frontend:yarnTest \
 *     --init-script gradle/java21.gradle --no-configuration-cache
 *
 * Usage:
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js [screenshot.png] [--no-seed]
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
const JAR = path.join(ROOT, 'backend/build/libs/backend-1.0-SNAPSHOT.jar');
const SAMPLE_DATA = path.join(ROOT, 'backend/src/test/resources/db/sample-data.sql');
const SMOKE_DB = path.join(ROOT, 'local-db/h2-smoke'); // disposable — never local-db/h2
const BASE_URL = 'http://localhost:8080';
const AUTH = { username: 'admin', password: 'admin' };

const args = process.argv.slice(2);
const seed = !args.includes('--no-seed');
const screenshotPath =
  args.find((a) => !a.startsWith('--')) || path.join(ROOT, 'playwright-smoke.png');

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
      const context = await browser.newContext({ httpCredentials: AUTH });
      const page = await context.newPage();
      await page.goto(BASE_URL);
      await page.waitForSelector('#root > *', { timeout: 15000 }); // React mounted
      if (seed) {
        // Both the sidebar's chat list (GET /api/chats) and the active chat's
        // messages (GET /{id}/messages) are fetched async after mount — #root
        // having children only proves the shell rendered, not that the seeded
        // chat and its messages have actually shown up yet.
        await page.waitForSelector('.chat-list-item', { timeout: 15000 });
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
