// ─── Метки оборванного ответа ────────────────────────────────────────────────
// Одна подпись на обрыв и вживую, и после перезагрузки. Вживую её дописывает редьюсер событий
// (RUN_STOPPED / RUN_ERROR), а в истории бэкенд хранит на её месте служебную метку
// (ChatRunService: STOPPED_MARKER / ERROR_MARKER) — и та же подпись обязана встать на её место.

import i18n from '@/i18n/index';

export const stoppedLabel = () => i18n.t('chat:window.stopped');
export const errorLabel = () => i18n.t('chat:window.genericError');
export const interruptedNote = () => `\n\n_**${i18n.t('chat:message.interrupted')}**_`;

// Метки бэкенда: одна строкой (прогон не написал текста) или через пустую строку после частичного
// ответа.
const STOPPED_MARKER = '[stopped]';
const ERROR_MARKER = '[error]';

/**
 * Сохранённый ответ с меткой обрыва — в том виде, в каком его показывал живой поток: подпись
 * вместо метки и флаг ошибки у упавшего (розовый пузырь; повтора у него нет — ответ из истории).
 *
 * @returns { text, error? } или null, если ответ не оборван
 */
export const interruptedAnswer = (content) => {
  const text = (content || '').trimEnd();
  const cut = (marker) => {
    if (text === marker) return '';
    return text.endsWith(`\n\n${marker}`) ? text.slice(0, -marker.length).trimEnd() : null;
  };
  const stopped = cut(STOPPED_MARKER);
  if (stopped != null) return { text: stopped ? `${stopped} ${stoppedLabel()}` : stoppedLabel() };
  const failed = cut(ERROR_MARKER);
  if (failed != null) return { text: failed ? failed + interruptedNote() : errorLabel(), error: true };
  return null;
};
