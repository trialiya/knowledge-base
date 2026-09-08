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
 * Раздел `sectionPath` в области `root` как отрезок DOM: `from` — его заголовок,
 * `to` — первый следующий заголовок не глубже (конец раздела вместе с его
 * подразделами), null — раздел тянется до конца области. Целиком null —
 * раздела в превью нет (заголовок с разметкой, которую нормализация не свела,
 * setext-заголовок, которого бэкенд не видит, или сам путь устарел вместе с
 * текстом). Преамбула заголовка не имеет: для неё тоже null, и поиск начинает
 * сначала.
 *
 * Сравниваем нормализованные ПОЛНЫЕ пути, а не сегменты: заголовок может сам
 * содержать « > », и разбить путь бэкенда однозначно нельзя. По той же причине
 * суффикс «[n]» не отделяется: «Примечание [2]» — законный заголовок, и только
 * сравнение пути целиком отличает его от второго «Примечание».
 */
export function findSection(root, sectionPath) {
  if (!sectionPath || sectionPath === PREAMBLE_PATH) return null;
  const wanted = normalizeTitle(sectionPath);
  const headings = headingPaths(root);
  const at = headings.findIndex((h) => h.path === wanted);
  if (at < 0) return null;
  const from = headings[at].el;
  const level = Number(from.tagName[1]);
  const to = headings.slice(at + 1).find((h) => Number(h.el.tagName[1]) <= level)?.el ?? null;
  return { from, to };
}
