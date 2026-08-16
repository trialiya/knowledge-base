// Что режим «Обзор» показывает для формы «дерево / оглавление»: структура базы
// знаний (`getTreeSkeleton`), оглавление документа (`getDocumentOutline`),
// символы файла (`getFileOutline`), файлы репозитория (`getFileTree`).
//
// Вложенность в этих ответах выражена по-разному — ссылкой на родителя, уровнем
// заголовка, диапазоном строк, путём, — но показывается одинаково. Поэтому здесь
// четыре сборщика и один нормализованный узел:
//
//   { key, label, secondary, meta: [{key, value}], children: [] }
//
// Разбор — по форме, а не по имени инструмента (см. registry.js).

import { carriesContentText } from './contentResult';

// Выше этого числа узлов вид не берётся: дерево такого размера не читают, его
// фильтруют, а фильтра здесь нет.
const MAX_NODES = 1000;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

const str = (value) => (typeof value === 'string' && value.trim() ? value : null);

const meta = (pairs) =>
  pairs
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => ({ key, value }));

// ── Сборщики ──────────────────────────────────────────────────────────────

/**
 * Ссылка на родителя (`getTreeSkeleton`): узлы плоские, иерархия в `parentId`.
 *
 * Узел, чьего родителя в выдаче нет, — корень: инструмент отдаёт поддерево, и
 * ссылка наружу это не ошибка.
 */
const byParentId = (records) => {
  const nodes = new Map();
  records.forEach((record, i) => {
    nodes.set(record.id, {
      key: `node-${i}`,
      label: str(record.title) ?? str(record.name) ?? String(record.id),
      secondary: null,
      meta: meta([['type', record.type]]),
      children: [],
    });
  });

  const roots = [];
  for (const record of records) {
    const node = nodes.get(record.id);
    const parent = record.parentId == null ? null : nodes.get(record.parentId);
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  }
  // Ни одного корня — в ссылках цикл: рисовать такое нельзя, а чинить нечем.
  return roots.length > 0 ? roots : null;
};

/**
 * Уровень заголовка (`getDocumentOutline`): секции идут по порядку, вложенность
 * задаёт `level` 1–6. Уровень 0 — преамбула: она не заголовок и никого не
 * усыновляет, поэтому на стек не кладётся.
 */
const byLevel = (sections) => {
  const roots = [];
  const stack = [];

  sections.forEach((section, i) => {
    const node = {
      key: `section-${i}`,
      label: str(section.title) ?? str(section.path) ?? '—',
      secondary: null,
      meta: meta([
        ['level', section.level > 0 ? `H${section.level}` : null],
        ['chars', section.chars],
      ]),
      children: [],
    };
    while (stack.length && stack[stack.length - 1].level >= section.level) stack.pop();
    (stack.length ? stack[stack.length - 1].node.children : roots).push(node);
    if (section.level > 0) stack.push({ level: section.level, node });
  });

  return roots;
};

/**
 * Диапазон строк (`getFileOutline`): символы идут в порядке появления в файле,
 * и метод оказывается внутри класса просто потому, что его строки внутри
 * строк класса.
 */
const bySpan = (symbols) => {
  const roots = [];
  const stack = [];

  symbols.forEach((symbol, i) => {
    const node = {
      key: `symbol-${i}`,
      label: str(symbol.name) ?? '—',
      secondary: str(symbol.signature),
      meta: meta([
        ['kind', symbol.kind],
        ['lines', symbol.endLine > symbol.startLine ? `${symbol.startLine}–${symbol.endLine}` : symbol.startLine],
      ]),
      children: [],
    };
    while (stack.length && stack[stack.length - 1].endLine < symbol.startLine) stack.pop();
    (stack.length ? stack[stack.length - 1].node.children : roots).push(node);
    stack.push({ endLine: symbol.endLine, node });
  });

  return roots;
};

/**
 * Путь (`getFileTree`, `searchFiles`): иерархия в самой строке пути.
 * Промежуточные каталоги достраиваются — в выдаче их может не быть вовсе.
 */
const byPath = (records) => {
  const roots = [];
  const index = new Map();

  const ensure = (path) => {
    const known = index.get(path);
    if (known) return known;

    const cut = path.lastIndexOf('/');
    const node = { key: `path-${path}`, label: path.slice(cut + 1), secondary: null, meta: [], children: [] };
    index.set(path, node);
    (cut < 0 ? roots : ensure(path.slice(0, cut)).children).push(node);
    return node;
  };

  for (const record of records) {
    ensure(record.path).meta = meta([
      ['type', record.type],
      ['size', record.size],
    ]);
  }
  return roots;
};

// ── Отбор ─────────────────────────────────────────────────────────────────

const countNodes = (nodes) => nodes.reduce((sum, node) => sum + 1 + countNodes(node.children), 0);

/** Список плоских записей → корни, либо null если ни один сборщик не подошёл. */
const fromArray = (records) => {
  if (!records.every(isPlainObject)) return null;
  // Список текстов — за content: делит формы тот же предикат, что и у recordList.
  if (records.some(carriesContentText)) return null;

  if (records.every((r) => Number.isInteger(r.id) && 'parentId' in r)) return byParentId(records);
  if (records.every((r) => str(r.path))) return byPath(records);
  return null;
};

/** Объект-обёртка со списком секций или символов → корни, либо null. */
const fromObject = (obj) => {
  const { sections, symbols } = obj;
  if (Array.isArray(sections) && sections.length > 0 && sections.every((s) => Number.isInteger(s.level))) {
    return byLevel(sections);
  }
  if (Array.isArray(symbols) && symbols.length > 0 && symbols.every((s) => Number.isInteger(s.startLine))) {
    return bySpan(symbols);
  }
  return null;
};

/** Шапка для форм-обёрток: у оглавления и обзора файла есть, у списков нет. */
const headerOf = (obj) =>
  isPlainObject(obj)
    ? {
        label: str(obj.title) ?? str(obj.path),
        meta: meta([
          ['language', obj.language],
          ['lineCount', obj.lineCount],
          ['parser', obj.parser],
          ['descriptionVersion', obj.descriptionVersion],
        ]),
      }
    : null;

/**
 * Разобранный ответ вызова → `{ header, nodes }` для `<TreeResultView>`, либо
 * null.
 */
export const detectTreeResult = ({ parsed, isJson }) => {
  if (!isJson) return null;

  let nodes = null;
  let header = null;
  if (Array.isArray(parsed)) {
    if (parsed.length === 0) return null;
    nodes = fromArray(parsed);
  } else if (isPlainObject(parsed)) {
    nodes = fromObject(parsed);
    header = nodes ? headerOf(parsed) : null;
  }

  if (!nodes || nodes.length === 0) return null;

  const count = countNodes(nodes);
  return count <= MAX_NODES ? { header, nodes, count } : null;
};
