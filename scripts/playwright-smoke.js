#!/usr/bin/env node
/**
 * Boots the backend jar (H2 profile, no real AI backend needed) and drives it with
 * Playwright's pre-installed Chromium to confirm the SPA actually renders. This is
 * the canonical example for "Visually validating the frontend" in CLAUDE.md — see
 * there for the full explanation (locale gotcha, auth, why the jar route).
 *
 * Prerequisites (build the jar first — this script does not):
 *   /opt/gradle/bin/gradle :backend:bootJar -x :frontend:yarnTest \
 *     --init-script gradle/java21.gradle --no-configuration-cache
 *
 * Usage:
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js [screenshot.png]
 *
 * Chromium and Playwright are pre-installed in the sandbox; do not run
 * `playwright install`.
 */
const { spawn } = require('child_process');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const JAR = path.join(ROOT, 'backend/build/libs/backend-1.0-SNAPSHOT.jar');
const BASE_URL = 'http://localhost:8080';
const AUTH = { username: 'admin', password: 'admin' };
const screenshotPath = process.argv[2] || path.join(ROOT, 'playwright-smoke.png');

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
  const backend = spawn('java', ['--enable-preview', '-jar', JAR], {
    cwd: ROOT,
    env: {
      ...process.env,
      // glibc's built-in UTF-8 locale — without it the JVM defaults to ASCII and
      // GitService throws on non-ASCII repo paths (docs/проект/*.md).
      LANG: 'C.utf8',
      LC_ALL: 'C.utf8',
      SPRING_PROFILES_ACTIVE: 'h2',
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

    const browser = await chromium.launch({
      executablePath: '/opt/pw-browsers/chromium', // stable symlink to the versioned binary
      args: ['--no-sandbox'],
    });
    try {
      const context = await browser.newContext({ httpCredentials: AUTH });
      const page = await context.newPage();
      await page.goto(BASE_URL);
      await page.waitForSelector('#root > *', { timeout: 15000 }); // React mounted
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
