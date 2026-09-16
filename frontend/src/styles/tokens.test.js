import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Сторож словаря токенов.
 *
 * Тема переключается подменой значений у ролей (styles/theme-*.css). Цвет,
 * написанный прямо в компоненте, этой подмены не увидит и останется светлым
 * пятном в тёмной теме — а заметить такое можно только глазами и только на том
 * экране, куда дошли. Поэтому проверяем механически.
 *
 * Здесь же держится и второй слой правила: сырая палитра (`--p-*`) компонентам
 * тоже не видна. `--p-neutral-0` белый в любой теме — взяв его напрямую,
 * компонент получает тот же вшитый цвет, только записанный длиннее.
 */
const SRC = dirname(dirname(fileURLToPath(import.meta.url)));
const TOKEN_DIR = join(SRC, 'styles');

/** Все .css проекта, кроме самих файлов темы. */
function cssFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return cssFiles(path);
    return e.isFile() && e.name.endsWith('.css') && !path.startsWith(TOKEN_DIR) ? [path] : [];
  });
}

/**
 * Строки объявлений без комментариев. Многострочный комментарий вырезаем,
 * оставляя его переводы строк: иначе нумерация уедет, и сторож укажет мимо.
 */
function declarations(path) {
  const text = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, (c) => c.replace(/[^\n]/g, ''));
  return text.split('\n').map((line, i) => ({ line, where: `${relative(SRC, path)}:${i + 1}` }));
}

const FILES = cssFiles(SRC);

/** Файлы тем: у каждой свой селектор, но список ролей обязан быть общим. */
const THEMES = ['theme-light.css', 'theme-dark.css'];

it('CSS-файлы проекта найдены — иначе сторож проверяет пустоту', () => {
  expect(FILES.length).toBeGreaterThan(50);
});

/*
 * Именованные цвета CSS: `white` красит ровно так же, как `#fff`, и так же
 * переживает подмену темы. Полный список не нужен — хватает тех, что человек
 * пишет руками; `transparent` и `currentColor` цветом темы не являются.
 */
const NAMED = String.raw`white|black|red|green|blue|gray|grey|silver|orange|yellow|pink|purple|brown|navy|teal|gold|crimson|violet|magenta|cyan|lime|beige|ivory|coral|salmon|khaki|lavender|plum|tan|olive|maroon|aqua|fuchsia`;

it('цвет задаётся ролью, а не значением', () => {
  const HARDCODED = new RegExp(String.raw`#[0-9a-fA-F]{3,8}\b|\brgba?\(|:[^;]*\b(${NAMED})\b`);
  const found = FILES.flatMap(declarations)
    .filter(({ line }) => HARDCODED.test(line))
    .map(({ line, where }) => `${where}: ${line.trim()}`);

  expect(found).toEqual([]);
});

it('компоненты берут роль (--kb-*), а не сырую палитру (--p-*)', () => {
  const found = FILES.flatMap(declarations)
    .filter(({ line }) => /var\(\s*--p-/.test(line))
    .map(({ line, where }) => `${where}: ${line.trim()}`);

  expect(found).toEqual([]);
});

/** Объявления `--имя:` в файле темы или палитры. */
function declared(file) {
  return [...readFileSync(join(TOKEN_DIR, file), 'utf8').matchAll(/^ {2}(--[a-z0-9-]+):/gm)].map((m) => m[1]);
}

/** Обращения `var(--имя)` во всём проекте, включая сами файлы тем. */
function referenced() {
  const themes = THEMES.map((file) => readFileSync(join(TOKEN_DIR, file), 'utf8'));
  const all = [...FILES.map((f) => readFileSync(f, 'utf8')), ...themes];
  return new Set(all.flatMap((text) => [...text.matchAll(/var\(\s*(--[a-z0-9-]+)/g)].map((m) => m[1])));
}

// Мёртвый токен не проверяется глазами: он ничего не красит, поэтому не ломается
// заметно — и тихо доживает до тёмной темы, где ему нужно будет второе значение.
it.each([
  ['палитре', 'palette.css'],
  ['теме', 'theme-light.css'],
])('в %s нет неиспользуемых токенов', (_name, file) => {
  const used = referenced();
  expect(declared(file).filter((token) => !used.has(token))).toEqual([]);
});

/*
 * Роль, забытая в одной из тем, не падает и не красит неправильно — она молча
 * берёт значение из другой: тёмная тема живёт под атрибутом, светлая на голом
 * `:root`, и незакрытая роль просто останется светлой. На тёмном экране это
 * одно белое пятно посреди страницы, и найти его можно только глазами и только
 * на том экране, куда дошли.
 */
it('темы отвечают на один и тот же список ролей', () => {
  const [light, ...rest] = THEMES.map((file) => declared(file));
  for (const other of rest) {
    expect([...other].sort()).toEqual([...light].sort());
  }
});
