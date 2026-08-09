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
 * Разрезать фразу на куски для отрисовки: обычный текст и плейсхолдеры между ним,
 * в порядке появления и с повторами. Кусок с `raw` — плейсхолдер, остальные несут
 * только `text`.
 *
 * @returns {({ text: string } | { raw: string, label: string, type: string })[]}
 */
export function splitPhrase(text) {
  const src = String(text ?? '');
  const parts = [];
  let done = 0;
  for (const m of src.matchAll(PLACEHOLDER_RE)) {
    if (m.index > done) parts.push({ text: src.slice(done, m.index) });
    parts.push({ raw: m[0], ...classify(m[1]) });
    done = m.index + m[0].length;
  }
  if (done < src.length) parts.push({ text: src.slice(done) });
  return parts;
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
  for (const part of splitPhrase(text)) {
    if (part.raw && !byRaw.has(part.raw)) byRaw.set(part.raw, part);
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
