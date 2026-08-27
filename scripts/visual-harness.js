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
 * built app (frontend/build/dist/static/*.css) — the fault is often the stand.
 *
 * Chromium and Playwright are pre-installed in the sandbox; do not run
 * `playwright install`.
 *
 * Каждый снимок сверяется с эталоном из frontend/tests/visual/baselines/ — они
 * лежат в git, и расхождение видно в прогоне, а не глазами. Кадр для этого
 * воспроизводим: каретка спрятана, анимации выключены, ответы сервера — из
 * фикстур, дат и случайных чисел в них нет. Расхождение пишет рядом со снимком
 * `<кейс>.diff.png`: несовпавшие пиксели красным поверх приглушённого эталона.
 * Правка интерфейса, из-за которой эталон устарел, принимается `--update` —
 * новые эталоны идут в коммит вместе с самой правкой, и в ревью видно, что
 * именно на экране изменилось.
 *
 * Эталоны принадлежат окружению рендера, а не репозиторию вообще: тот же
 * Chromium с другим набором шрифтов рисует иначе. Их окружение — образ
 * mcr.microsoft.com/playwright из ежедневного прогона (.github/workflows/
 * frontend-main-daily.yml), отпечаток лежит рядом с ними в environment.json, и
 * в чужом окружении сверка пропускается со строкой в отчёте. Локально прогон в
 * этом образе — ./run/test.sh harness-image (там же и `-- --update`).
 *
 * Usage:
 *   ./run/test.sh harness                    # every case, into harness/shots/
 *   ./run/test.sh harness -- chatRepo.js#repoTabMerging      # one case
 *   ./run/test.sh harness-image              # то же со сверкой с эталонами
 *   ./run/test.sh harness-image -- --update  # переснять эталоны
 *   NODE_PATH=/opt/node22/lib/node_modules node scripts/visual-harness.js \
 *     [caseId ...] [--no-build] [--update] [--locale=ru|en] [--port=8099] \
 *     [--out=<dir>] [--baselines=<dir>]
 *
 * Case ids are the `fixtures:` references from frontend/tests/visual/cases.yaml
 * (`<module>#<export>`, plus `@variant` where one fixture is drawn by more than
 * one component); the opt-in list is harness/registry.jsx, where a case may also
 * name the steps to take before the shot (click, keypress, typing) and the
 * server answers to hand the component. Run with no ids to shoot them all — the
 * summary line per case reports the console errors the page produced, which is
 * half of what the run is for.
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
const baseDir = path.resolve(ROOT, flag('baselines', path.join(HARNESS, '..', 'baselines')));
const update = args.includes('--update');
// Порог связной области, ниже которого расхождение считается дрожанием
// сглаживания (см. compare). Замерено: крапины на скруглениях карточек — до
// четырёх пикселей, самая мелкая настоящая правка (одна буква) — за полсотни.
const minCluster = Number(flag('min-cluster', '12'));
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

/**
 * Case ids, read out of the built bundle's own registry so the two cannot drift:
 * the stand's index page lists every case, each link tagged with its id. Reading
 * the source instead would mean re-parsing JSX here — and would miss the ids a
 * registry entry builds rather than spells out (the tool-call cases are one map
 * over a list of fixture names).
 */
/**
 * Отпечаток окружения рендера: одни и те же эталоны имеют смысл только там, где
 * пиксели получаются те же. Замерено: тот же Chromium в контейнере playwright
 * рисует иначе, чем в песочнице (другой набор шрифтов — и 42 кейса из 56
 * расходятся), поэтому эталоны принадлежат окружению, а не репозиторию вообще.
 *
 * В отпечатке — версия браузера и ширины строки-пробы в тех же стеках шрифтов,
 * которыми набран интерфейс: именно они и разъезжаются, когда fontconfig выдал
 * другое семейство. Не совпало — сверку пропускаем с явной строкой в отчёте, а
 * не красим прогон: чужой эталон говорит не о правке интерфейса, а о том, что
 * снимали в другом месте.
 */
async function fingerprint(browser, page) {
  const PROBE = 'Ag—0О9 gradle/build.gradle 18.07.2026';
  const widths = await page.evaluate((probe) => {
    const measure = (font, weight) => {
      const el = document.createElement('span');
      el.style.cssText = `position:fixed;left:-9999px;white-space:pre;font:${weight} 14px ${font}`;
      el.textContent = probe;
      document.body.appendChild(el);
      const width = el.getBoundingClientRect().width.toFixed(2);
      el.remove();
      return width;
    };
    const system = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    const mono = "'Fira Mono', 'Consolas', monospace";
    return {
      system: measure(system, 400),
      systemBold: measure(system, 700),
      mono: measure(mono, 400),
    };
  }, PROBE);
  return { chromium: browser.version(), widths };
}

/**
 * Кейсы стенда: имя и окно, в котором его снимают. Окно приходит со страницы,
 * а не из второго списка здесь — реестр кейсов один (registry.jsx).
 */
async function caseIds(page) {
  await page.goto(`http://127.0.0.1:${port}/index.html`);
  await page.waitForFunction(() => document.documentElement.dataset.harness === 'ready');
  return page.$$eval('[data-case-id]', (nodes) =>
    nodes.map((n) => ({ id: n.dataset.caseId, viewport: n.dataset.caseViewport ? JSON.parse(n.dataset.caseViewport) : null })),
  );
}

/**
 * Сравнение снимка с эталоном — в том же Chromium, которым снимали: canvas у нас
 * уже есть, а библиотека сравнения картинок из npm жила бы в зависимостях
 * frontend/, откуда скрипту её не достать (он запускается с NODE_PATH на
 * глобальные модули песочницы).
 *
 * Пиксель считается разошедшимся при любом отличии канала — допуск по цвету
 * скрывал бы настоящую правку оттенка, а не шум. Шум здесь другой: скруглённые
 * углы карточек стоят на дробных координатах (замерено: 108.766, 287.828 — и
 * от прогона к прогону они те же), но растеризуются через раз по-разному, давая
 * на каждом углу крапину в два-четыре пикселя.
 *
 * Отсюда мера — не доля разошедшихся пикселей, а размер связной области, в
 * которую они складываются: `minCluster` отсекает эти крапины, а настоящая
 * правка (другая буква, сдвинутая рамка, другой цвет заливки) даёт область на
 * порядок больше. Мелочь всё равно считается и печатается: если её станет
 * много, это уже не дрожание, и правило пора пересматривать.
 *
 * @returns {{ pixels: number, specks: number, biggest: number, total: number,
 *   image: string|null, size: string|null }} `pixels` — в областях крупнее
 *   порога, `specks` — в отсеянных крапинах, `biggest` — самая большая область.
 *   `size` заполнен, когда снимок и эталон разных размеров: сравнивать
 *   попиксельно уже нечего, и это само по себе расхождение.
 */
async function compare(page, shotFile, baseFile, minCluster) {
  const url = (file) => `data:image/png;base64,${fs.readFileSync(file).toString('base64')}`;
  return page.evaluate(async ([shotUrl, baseUrl, floor]) => {
    const load = (src) =>
      new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = reject;
        img.src = src;
      });
    const [shot, base] = await Promise.all([load(shotUrl), load(baseUrl)]);
    if (shot.width !== base.width || shot.height !== base.height) {
      return {
        pixels: 0,
        specks: 0,
        biggest: 0,
        total: 0,
        image: null,
        size: `${shot.width}×${shot.height} против ${base.width}×${base.height}`,
      };
    }

    const pixelsOf = (img) => {
      const canvas = new OffscreenCanvas(img.width, img.height);
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0);
      return ctx.getImageData(0, 0, img.width, img.height);
    };
    const a = pixelsOf(shot);
    const b = pixelsOf(base);

    const { width, height } = shot;
    const total = width * height;
    const changed = new Uint8Array(total);
    for (let p = 0; p < total; p += 1) {
      const i = p * 4;
      changed[p] =
        a.data[i] !== b.data[i] ||
        a.data[i + 1] !== b.data[i + 1] ||
        a.data[i + 2] !== b.data[i + 2] ||
        a.data[i + 3] !== b.data[i + 3]
          ? 1
          : 0;
    }

    // Связные области разошедшихся пикселей: обход в ширину по четырём соседям,
    // очередь — плоский массив, потому что областей бывает много, а рекурсия на
    // области в тысячи пикселей упёрлась бы в стек.
    const CLEAN = 0;
    const SPECK = 1;
    const BLOB = 2;
    const kind = new Uint8Array(total);
    const queue = new Int32Array(total);
    let pixels = 0;
    let specks = 0;
    let biggest = 0;
    for (let start = 0; start < total; start += 1) {
      if (!changed[start] || kind[start] !== CLEAN) continue;
      let head = 0;
      let tail = 0;
      queue[tail++] = start;
      kind[start] = SPECK;
      while (head < tail) {
        const p = queue[head++];
        const x = p % width;
        if (x > 0 && changed[p - 1] && kind[p - 1] === CLEAN) kind[queue[tail++] = p - 1] = SPECK;
        if (x < width - 1 && changed[p + 1] && kind[p + 1] === CLEAN) kind[queue[tail++] = p + 1] = SPECK;
        if (p >= width && changed[p - width] && kind[p - width] === CLEAN) kind[queue[tail++] = p - width] = SPECK;
        if (p < total - width && changed[p + width] && kind[p + width] === CLEAN)
          kind[queue[tail++] = p + width] = SPECK;
      }
      if (tail > biggest) biggest = tail;
      if (tail >= floor) {
        pixels += tail;
        for (let q = 0; q < tail; q += 1) kind[queue[q]] = BLOB;
      } else specks += tail;
    }

    // Картинка расхождения: эталон, приглушённый до бледного фона, и поверх него
    // красным то, что разошлось — так видно и что изменилось, и где на экране.
    // Отсеянные крапины рисуем тоже, но бледнее: они видны и не спорят за
    // внимание с настоящим пятном.
    const out = new ImageData(width, height);
    for (let p = 0; p < total; p += 1) {
      const i = p * 4;
      const colour = kind[p] === BLOB ? [226, 32, 74] : kind[p] === SPECK ? [246, 173, 190] : null;
      if (colour) {
        out.data[i] = colour[0];
        out.data[i + 1] = colour[1];
        out.data[i + 2] = colour[2];
      } else {
        out.data[i] = 255 - (255 - b.data[i]) * 0.15;
        out.data[i + 1] = 255 - (255 - b.data[i + 1]) * 0.15;
        out.data[i + 2] = 255 - (255 - b.data[i + 2]) * 0.15;
      }
      out.data[i + 3] = 255;
    }
    if (!pixels) return { pixels, specks, biggest, total, image: null, size: null };

    const canvas = new OffscreenCanvas(shot.width, shot.height);
    canvas.getContext('2d').putImageData(out, 0, 0);
    const blob = await canvas.convertToBlob({ type: 'image/png' });
    const buffer = await blob.arrayBuffer();
    let binary = '';
    new Uint8Array(buffer).forEach((byte) => {
      binary += String.fromCharCode(byte);
    });
    return { pixels, specks, biggest, total, image: btoa(binary), size: null };
  }, [url(shotFile), url(baseFile), minCluster]);
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

  fs.mkdirSync(outDir, { recursive: true });
  const server = await serve(DIST);
  // В песочнице Chromium лежит рядом с ней самой, а не там, где его ищет
  // playwright по умолчанию; в контейнере (и на раннере) — наоборот, и путь
  // песочницы там просто не существует.
  const sandboxChromium = '/opt/pw-browsers/chromium';
  const browser = await chromium.launch(
    fs.existsSync(sandboxChromium) ? { executablePath: sandboxChromium } : {},
  );
  const context = await browser.newContext({
    locale,
    viewport: { width: 1440, height: 900 },
    // Тот же уговор, что и с кареткой: снимок обязан быть одним и тем же кадром
    // от прогона к прогону, а переход, начатый на монтировании, к нему не готов.
    reducedMotion: 'reduce',
  });
  let failed = 0;

  const indexPage = await context.newPage();
  const known = await caseIds(indexPage);
  await indexPage.close();
  // Отдельная пустая страница под сравнение: на странице кейса чужой canvas
  // попал бы в её же консольные ошибки, а сам кейс — под шаги сравнения.
  const diffPage = await context.newPage();

  // Своё ли это окружение для эталонов — решаем один раз на прогон.
  const envFile = path.join(baseDir, 'environment.json');
  const here = await fingerprint(browser, diffPage);
  const stored = fs.existsSync(envFile) ? JSON.parse(fs.readFileSync(envFile, 'utf8')) : null;
  const sameEnv = stored && JSON.stringify(stored) === JSON.stringify(here);
  if (update) {
    fs.mkdirSync(baseDir, { recursive: true });
    fs.writeFileSync(envFile, `${JSON.stringify(here, null, 2)}\n`);
  } else if (!stored) {
    console.log('· эталонов ещё нет — прогон только снимает; заведите их через --update');
  } else if (!sameEnv) {
    console.log(
      '· сверка с эталонами пропущена: они сняты в другом окружении рендера ' +
        `(${stored.chromium}, пробы ${Object.values(stored.widths).join('/')} против ` +
        `${here.chromium}, ${Object.values(here.widths).join('/')}). ` +
        'Эталоны принадлежат контейнеру из ежедневного прогона — см. скилл frontend-visual-check.',
    );
  }
  const names = known.map((c) => c.id);
  const ids = wanted.length ? wanted : names;
  const unknown = ids.filter((id) => !names.includes(id));
  if (unknown.length) {
    console.error(`Нет таких кейсов: ${unknown.join(', ')}`);
    console.error(`Известные:\n  ${names.join('\n  ')}`);
    await browser.close();
    server.close();
    process.exit(2);
  }

  for (const id of ids) {
    const page = await context.newPage();
    // Своё окно кейса. Рамки стенда высотой в экран и прокручиваются внутри
    // себя, поэтому длинную колонку настроек не спасает ни fullPage, ни скролл:
    // в кадр попадает ровно столько, сколько заказано высоты.
    const box = known.find((c) => c.id === id)?.viewport;
    if (box) await page.setViewportSize({ width: box[0], height: box[1] });
    const problems = [];
    page.on('console', (m) => m.type() === 'error' && problems.push(m.text()));
    page.on('pageerror', (e) => problems.push(String(e)));

    await page.goto(`http://127.0.0.1:${port}/index.html?case=${encodeURIComponent(id)}`);
    // The dictionary is a separate chunk and the first frame waits for it. The
    // flag sits on <html>: a modal portals into document.body, leaving #root empty.
    await page.waitForFunction(() => document.documentElement.dataset.harness === 'ready');
    // A state the component opens itself — a dropdown menu, a find bar, the text
    // typed into it — is in no fixture's reach: the case names the steps,
    // main.jsx passes them through.
    const steps = JSON.parse((await page.evaluate(() => document.documentElement.dataset.harnessSteps)) || '[]');
    for (const step of steps) {
      // A step that no longer lands is this case's failure, not the run's: left
      // to reject, it would take the browser and the server down with it and
      // skip every case after this one, naming none of them. Short timeout —
      // nothing is loading any more, the element is either there or it is not.
      const run = step.click
        ? page.click(step.click, { timeout: 2000 })
        : step.press
          ? page.keyboard.press(step.press)
          : page.keyboard.type(step.type);
      await run.catch((e) => problems.push(`${JSON.stringify(step)}: ${e.message}`));
      // Между шагами — кадр: клик открывает меню, а следующий шаг метит в то,
      // чего до этого кадра в DOM ещё нет.
      await page.waitForTimeout(50);
    }
    await page.waitForTimeout(200);

    const name = `${id.replace(/[^\w.-]+/g, '-')}.png`;
    const file = path.join(outDir, name);
    // Каретка и анимации — единственное на этих экранах, что меняется само:
    // кейс с переименованием снимается с полем в фокусе, и мигающая каретка
    // расходилась бы с эталоном через раз.
    await page.screenshot({ path: file, caret: 'hide', animations: 'disabled' });
    // A page that rendered nothing but the "no such fixture" note is a broken
    // registry, and a silent green run would hide it behind a valid-looking PNG.
    const empty = await page.evaluate(() => !!document.querySelector('#root > pre'));

    // Сверка с эталоном — только когда снимку можно верить: у пустого кейса или
    // кейса с ошибками в консоли сначала чинят их, а не эталон.
    let verdict = '';
    const baseFile = path.join(baseDir, name);
    if (!empty && !problems.length && (update || sameEnv)) {
      if (update || !fs.existsSync(baseFile)) {
        fs.mkdirSync(baseDir, { recursive: true });
        fs.copyFileSync(file, baseFile);
        verdict = update ? ' (эталон обновлён)' : ' (эталон заведён)';
      } else {
        const { pixels, specks, biggest, total, image, size } = await compare(diffPage, file, baseFile, minCluster);
        if (size) problems.push(`размер снимка не совпал с эталоном: ${size}`);
        else if (pixels) {
          const diffFile = path.join(outDir, name.replace(/\.png$/, '.diff.png'));
          fs.writeFileSync(diffFile, Buffer.from(image, 'base64'));
          problems.push(
            `${pixels} из ${total} пикселей разошлись с эталоном, ` +
              `самая большая область — ${biggest} → ${path.relative(ROOT, diffFile)}`,
          );
        }
      }
    }

    const bad = empty || problems.length;
    if (bad) failed += 1;
    console.log(`${bad ? '✗' : '✓'} ${id} → ${path.relative(ROOT, file)}${verdict}`);
    problems.forEach((p) => console.log(`    ${p}`));
    await page.close();
  }

  await browser.close();
  server.close();
  process.exit(failed ? 1 : 0);
}

main();
