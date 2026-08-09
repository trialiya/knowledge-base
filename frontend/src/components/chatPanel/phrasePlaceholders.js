// ─── Плейсхолдеры фразы ──────────────────────────────────────────────────────
// Фраза хранится обычной строкой, тип плейсхолдера живёт в самом литерале:
//   {{Имя файла}}       — без типа, значит string
//   {{Имя файла:file}}  — явный тип
// Схема БД не меняется: колонка `phrase.text` как была простой строкой, так и
// остаётся, а старые фразы без типов читаются новым парсером без миграции.

/** Типы, у которых есть свой виджет ввода; всё остальное после «:» — обычный текст. */
export const PLACEHOLDER_TYPES = ['file', 'commit', 'document', 'number', 'string', 'boolean'];

const DEFAULT_TYPE = 'string';

// Внутри плейсхолдера нет ни скобок, ни переноса строки: без запрета на перенос
// одинокая «{{» в одной строке склеилась бы с «}}» абзацем ниже.
const PLACEHOLDER_RE = /\{\{([^{}\n]+?)\}\}/g;

/**
 * Разобрать внутренность плейсхолдера на подпись и тип.
 *
 * Тип отделяется ПОСЛЕДНИМ двоеточием и только если он известен — иначе всё
 * содержимое становится подписью строкового поля. Так «{{Время 10:30}}» остаётся
 * подписью с двоеточием, а опечатка в типе («{{Файл:fiel}}») даёт обычное
 * текстовое поле вместо молча пропавшего плейсхолдера.
 */
function classify(inner) {
  const colon = inner.lastIndexOf(':');
  if (colon > 0) {
    const type = inner
      .slice(colon + 1)
      .trim()
      .toLowerCase();
    const label = inner.slice(0, colon).trim();
    if (label && PLACEHOLDER_TYPES.includes(type)) return { label, type };
  }
  return { label: inner.trim(), type: DEFAULT_TYPE };
}

/**
 * Плейсхолдеры фразы в порядке появления, без повторов.
 *
 * Ключ дедупликации — сам литерал (`raw`), поэтому «{{Файл:file}}» дважды это одно
 * поле на два вхождения, а «{{Файл}}» и «{{Файл:file}}» — два разных поля: у них
 * разные типы, и общее значение им не подходит.
 *
 * @returns {{ raw: string, label: string, type: string }[]}
 */
export function parsePlaceholders(text) {
  const byRaw = new Map();
  for (const m of String(text ?? '').matchAll(PLACEHOLDER_RE)) {
    if (!byRaw.has(m[0])) byRaw.set(m[0], { raw: m[0], ...classify(m[1]) });
  }
  return [...byRaw.values()];
}

/**
 * Подставить значения на место плейсхолдеров; всё, чего нет в `values`, остаётся
 * литералом. Пустая строка — это значение, а не пропуск: поле, которое осознанно
 * оставили пустым, стирает плейсхолдер.
 *
 * @param {Record<string, string>} values — ключ это `raw` плейсхолдера
 */
export function fillPlaceholders(text, values) {
  return String(text ?? '').replace(PLACEHOLDER_RE, (raw) => values[raw] ?? raw);
}
