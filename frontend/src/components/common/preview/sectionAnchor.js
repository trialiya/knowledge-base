// ─── Раздел из адреса → заголовок в превью ───────────────────────────────────
// Поиск по документам адресует найденное путём раздела в форме бэкенда
// (MarkdownSections: заголовки-предки через « > », повтор пути — суффикс «[n]»,
// текст до первого заголовка — «_preamble»). Превью ставит заголовкам только
// слаги (rehype-slug), которые к такому пути не сводятся, поэтому путь
// считается здесь заново, по отрендеренным h1–h6, тем же правилом.

const PATH_SEPARATOR = ' > ';

/** Путь псевдораздела «до первого заголовка»: прыгать к нему — значит в начало. */
export const PREAMBLE_PATH = '_preamble';

/**
 * Один вид заголовка для обеих сторон. Бэкенд хранит его сырым — `**Docker**`,
 * `` `kb.tools` ``, `[текст](url)` — а textContent отрендеренного заголовка
 * маркеров уже не несёт; срезаем их и там, где они есть, и там, где их нет,
 * чтобы сравнивать одно с одним. Пробелы схлопываем: перенос внутри заголовка
 * в DOM и пробел в markdown — одно и то же.
 */
export function normalizeTitle(title) {
  return (title || '')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_`~]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Заголовки области с их путями — в порядке документа, как MarkdownSections
 * .parse считает их по сырому markdown: стек предков по уровню, повтор полного
 * пути получает «[n]».
 */
function headingPaths(root) {
  const stack = [];
  const counts = new Map();
  const result = [];
  for (const el of root.querySelectorAll('h1, h2, h3, h4, h5, h6')) {
    const level = Number(el.tagName[1]);
    while (stack.length && stack[stack.length - 1].level >= level) stack.pop();
    stack.push({ level, title: normalizeTitle(el.textContent) });
    const path = stack.map((h) => h.title).join(PATH_SEPARATOR);
    const occurrence = (counts.get(path) || 0) + 1;
    counts.set(path, occurrence);
    result.push({ el, path: occurrence === 1 ? path : `${path}[${occurrence}]` });
  }
  return result;
}

/**
 * Заголовок раздела `sectionPath` в области `root`, либо null — раздела в
 * превью нет (заголовок с разметкой, которую нормализация не свела, setext-
 * заголовок, которого бэкенд не видит, или сам путь устарел вместе с текстом).
 * Преамбула заголовка не имеет: для неё тоже null, и поиск начинает сначала.
 *
 * Сравниваем нормализованные ПОЛНЫЕ пути, а не сегменты: заголовок может сам
 * содержать « > », и разбить путь бэкенда однозначно нельзя.
 */
export function findSectionHeading(root, sectionPath) {
  if (!sectionPath || sectionPath === PREAMBLE_PATH) return null;
  const suffix = sectionPath.match(/\[(\d+)\]$/);
  const wanted =
    normalizeTitle(suffix ? sectionPath.slice(0, -suffix[0].length) : sectionPath) + (suffix ? suffix[0] : '');
  return headingPaths(root).find((h) => h.path === wanted)?.el ?? null;
}
