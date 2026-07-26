/** Человекочитаемый размер файла (B / KB / MB). */
export function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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
