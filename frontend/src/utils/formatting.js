/** Человекочитаемый размер файла (B / KB / MB). */
export function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Длительность в секундах → { value, unit } для перевода на стороне компонента:
 * unit ∈ seconds | minutes | hours. Возвращаем пару, а не готовую строку, потому
 * что суффикс — пользовательский текст и обязан жить в локалях (en + ru), а не в
 * утилите. Округляем только вниз до целых единиц: 600 → 10 минут, 90 → 90 секунд.
 */
export function splitDuration(seconds) {
  if (seconds >= 3600 && seconds % 3600 === 0) return { value: seconds / 3600, unit: 'hours' };
  if (seconds >= 60 && seconds % 60 === 0) return { value: seconds / 60, unit: 'minutes' };
  return { value: seconds, unit: 'seconds' };
}

/**
 * Дата-время в локали интерфейса, либо null для пустого/битого значения.
 *
 * Общий формат для вкладок «Инфо» всех разделов: чат, документ и коммит файла
 * должны выглядеть одинаково. null (а не «—») — чтобы InfoList сам решал, что
 * строку показывать не нужно.
 */
export function formatDateTime(value, locale) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString(locale);
}

/**
 * Только дата в локали интерфейса, либо null для пустого/битого значения — там,
 * где время в значении не стояло вовсе и 00:00 было бы артефактом разбора.
 */
export function formatDate(value, locale) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString(locale);
}

/**
 * Дата словами и время без секунд («20 мая 2026 г., 06:58» в ru) — для подсказки
 * над относительным временем сообщения, где читают её целиком, а не сравнивают
 * в столбик. null для пустого или битого значения.
 */
export function formatLongDateTime(value, locale) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleString(locale, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Дата и время цифрами, без секунд («20.05.2026, 06:58» в ru) — для колонки
 * blame: у подписей одна ширина, и описания коммитов за ними начинаются ровно в
 * столбик. null для пустого или битого значения.
 */
export function formatCompactDateTime(value, locale) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// Подписи, идущие за тиком `useNow`, форматируются каждую секунду: собирать
// Intl-форматтер на каждый вызов было бы дороже самой подписи.
const relativeFormats = new Map();
function relativeFormat(locale) {
  let rtf = relativeFormats.get(locale);
  if (!rtf) {
    rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
    relativeFormats.set(locale, rtf);
  }
  return rtf;
}

/**
 * Момент относительно «сейчас» в локали интерфейса: «5 минут назад», «2 часа
 * назад», дальше суток — короткая дата, в другом году — с годом (иначе два
 * коммита с разницей в годы читались бы одинаково). Плюрализацию и слова даёт
 * нативный Intl.RelativeTimeFormat, поэтому ключей перевода не нужно. null для
 * пустого или битого значения; момент из будущего (часы разошлись) — датой, а
 * не пустотой. `nowMs` — от чего отсчитывать; без него — от момента вызова.
 */
export function formatRelativeTime(value, locale, nowMs) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  const now = nowMs == null ? new Date() : new Date(nowMs);
  const diffMin = Math.floor((now.getTime() - date.getTime()) / 60000);
  // −1 — момент чуть позже «сейчас»: отметка поставлена в этой же вкладке после
  // последнего тика `useNow` или часы сервера на секунды впереди. Это «сейчас», не дата.
  if (diffMin >= -1 && diffMin < 60 * 24) {
    const rtf = relativeFormat(locale);
    if (diffMin < 1) return rtf.format(0, 'minute');
    if (diffMin < 60) return rtf.format(-diffMin, 'minute');
    return rtf.format(-Math.floor(diffMin / 60), 'hour');
  }
  const sameYear = date.getFullYear() === now.getFullYear();
  return date.toLocaleDateString(locale, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) });
}

/**
 * Меняет ли течение времени подпись `formatRelativeTime` для этого значения:
 * да, пока момент ближе суток к «сейчас» в любую сторону — «N минут/часов
 * назад» или дата из ближайшего будущего (часы разошлись), которая скоро станет
 * «сейчас»; нет — для даты дальше суток.
 */
export function isRelativeTimeLive(value, nowMs = Date.now()) {
  if (!value) return false;
  const time = new Date(value).getTime();
  return !Number.isNaN(time) && Math.abs(nowMs - time) < 24 * 60 * 60000;
}
