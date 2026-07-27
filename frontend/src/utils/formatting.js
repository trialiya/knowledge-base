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
