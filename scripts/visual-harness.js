#!/usr/bin/env node
/**
 * Screenshots single components against their fixtures, with no backend at all.
 *
 * The other half of the frontend-visual-check skill (.claude/skills/). Its
 * sibling, playwright-smoke.js, boots the whole app and drives real screens —
 * the only way to check anything involving live data. This one covers what the
 * app cannot be talked into showing on demand: an unfinished merge, a chat with
 * the model mid-answer, a repository that refuses a push. Those states live in
 * frontend/tests/visual/fixtures/ and are rendered straight into a page.
 *
 * What it does NOT do is replace the smoke run. A component here is mounted in
 * a frame the harness builds (see harness/main.jsx), not in the real layout, so
 * anything decided by the surrounding page — the tab strip, the panel header,
 * neighbouring sections — is out of its reach and stays a smoke-run question.
 * Twice already the harness itself has been the thing that was wrong: a CSS
 * bundle ordered differently from the app's showed a 380px modal that is 560px
 * in the product, and a missing buttons.css showed browser-default buttons.
 * Before reporting anything the harness shows as a defect, check it against the
 * built app (frontend/build/static/*.css) — the fault is often the stand.
 *
 * Chromium and Playwright are pre-installed in the sandbox; do not run
 * `playwright install`.
 *
 * Usage:
 *   ./run/test.sh harness                    # every case, into harness/shots/
 *   ./run/test.sh harness -- chatRepo.js#repoTabMerging      # one case
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/visual-harness.js \
 *     [caseId ...] [--no-build] [--locale=ru|en] [--port=8099] [--out=<dir>]
 *
 * Case ids are the `fixtures:` references from frontend/tests/visual/cases.yaml
 * (`<module>#<export>`); the opt-in list is harness/registry.jsx, where a case
 * may also name a selector to click before the shot. Run with no
 * ids to shoot them all — the summary line per case reports the console errors
 * the page produced, which is half of what the run is for.
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const HARNESS = path.join(ROOT, 'frontend/tests/visual/harness');
const DIST = path.join(HARNESS, 'dist');

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const build = !args.includes('--no-build');
// The app's own fallbackLng, not the browser's: this sandbox's Chromium reports
// en-US, and i18next would quietly render the English strings (see the skill).
const locale = flag('locale', 'ru');
const port = Number(flag('port', '8099'));
const outDir = path.resolve(ROOT, flag('out', path.join(HARNESS, 'shots')));
const wanted = args.filter((a) => !a.startsWith('--'));

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

/** Static server over the built bundle. `file://` will not do — the CSP blocks module scripts there. */
function serve(dir) {
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
    const file = path.join(dir, rel);
    // The browser asks for a favicon on its own; a 404 for it would show up in
    // every case's console-error list and hide the errors worth reading.
    if (rel === 'favicon.ico') {
      res.writeHead(204).end();
      return;
    }
    if (!file.startsWith(dir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404).end('not found');
      return;
    }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

/** Case ids, read out of the built bundle's own registry so the two cannot drift. */
function caseIds() {
  const source = fs.readFileSync(path.join(HARNESS, 'registry.jsx'), 'utf8');
  return [...source.matchAll(/id: '([^']+)'/g)].map((m) => m[1]);
}

async function main() {
  if (build) {
    const built = spawnSync('npx', ['vite', 'build', '--config', path.join(HARNESS, 'vite.config.js')], {
      cwd: path.join(ROOT, 'frontend'),
      stdio: 'inherit',
    });
    if (built.status !== 0) process.exit(built.status ?? 1);
  }
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('Стенд не собран — уберите --no-build.');
    process.exit(1);
  }

  const ids = wanted.length ? wanted : caseIds();
  const unknown = ids.filter((id) => !caseIds().includes(id));
  if (unknown.length) {
    console.error(`Нет таких кейсов: ${unknown.join(', ')}`);
    console.error(`Известные:\n  ${caseIds().join('\n  ')}`);
    process.exit(2);
  }

  fs.mkdirSync(outDir, { recursive: true });
  const server = await serve(DIST);
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const context = await browser.newContext({ locale, viewport: { width: 1440, height: 900 } });
  let failed = 0;

  for (const id of ids) {
    const page = await context.newPage();
    const problems = [];
    page.on('console', (m) => m.type() === 'error' && problems.push(m.text()));
    page.on('pageerror', (e) => problems.push(String(e)));

    await page.goto(`http://127.0.0.1:${port}/index.html?case=${encodeURIComponent(id)}`);
    // The dictionary is a separate chunk and the first frame waits for it. The
    // flag sits on <html>: a modal portals into document.body, leaving #root empty.
    await page.waitForFunction(() => document.documentElement.dataset.harness === 'ready');
    // A state the component opens itself — a dropdown menu — is in no fixture's
    // reach: the case names the selector to click, main.jsx passes it through.
    const click = await page.evaluate(() => document.documentElement.dataset.harnessClick);
    if (click) await page.click(click);
    await page.waitForTimeout(200);

    const file = path.join(outDir, `${id.replace(/[^\w.-]+/g, '-')}.png`);
    await page.screenshot({ path: file });
    // A page that rendered nothing but the "no such fixture" note is a broken
    // registry, and a silent green run would hide it behind a valid-looking PNG.
    const empty = await page.evaluate(() => !!document.querySelector('#root > pre'));
    if (empty || problems.length) failed += 1;
    console.log(`${empty || problems.length ? '✗' : '✓'} ${id} → ${path.relative(ROOT, file)}`);
    problems.forEach((p) => console.log(`    ${p}`));
    await page.close();
  }

  await browser.close();
  server.close();
  process.exit(failed ? 1 : 0);
}

main();
