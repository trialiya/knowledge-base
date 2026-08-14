// ─── Уведомления чата ────────────────────────────────────────────────────────
// Дескрипторы для useNotice: иконка плюс ключи перевода. Собраны в одном месте,
// потому что рисует их все один `<ErrorModal>` в ChatWindow, а поводы приходят
// из разных хуков — так видно сразу весь набор и не разъезжаются иконки.
//
// Ключи даны без префикса пространства имён: их резолвит `t` из ChatWindow,
// а он привязан к `chat`.

// Код статуса в скобках, если это не сетевой сбой (у того кода нет).
const suffixOf = (status) => (status !== 'network' ? ` (${status})` : '');

/** Чат не открылся: битая ссылка (404) либо отказ сервера. */
export const chatLoadErrorNotice = ({ notFound, status }) =>
  notFound
    ? { icon: '🔍', titleKey: 'errorModal.notFoundTitle', messageKey: 'errorModal.notFoundMessage' }
    : {
        icon: '⚠️',
        titleKey: 'errorModal.loadErrorTitle',
        messageKey: 'errorModal.loadErrorMessage',
        params: { suffix: suffixOf(status) },
      };

/** Сервер отказался удалять чат. */
export const chatDeleteErrorNotice = (status) => ({
  icon: '⚠️',
  titleKey: 'errorModal.deleteErrorTitle',
  messageKey: 'errorModal.deleteErrorMessage',
  params: { suffix: suffixOf(status) },
});

/** В чате уже идёт генерация (ответ 409 на старт прогона). */
export const RUN_BUSY_NOTICE = {
  icon: '⏳',
  titleKey: 'errorModal.busyTitle',
  messageKey: 'errorModal.busyMessage',
};

/** Повтор пришёл слишком поздно — бэк ответил 422. */
export const RETRY_UNAVAILABLE_NOTICE = {
  icon: '↻',
  titleKey: 'errorModal.retryUnavailableTitle',
  messageKey: 'errorModal.retryUnavailableMessage',
};

/** Открытый чат удалили в другой вкладке (событие CHAT_DELETED). */
export const CHAT_DELETED_NOTICE = {
  icon: '🗑️',
  titleKey: 'errorModal.deletedTitle',
  messageKey: 'errorModal.deletedMessage',
};

/** Не удалось загрузить файл, приложенный из композера. */
export const UPLOAD_ERROR_NOTICE = {
  icon: '⚠️',
  titleKey: 'errorModal.uploadTitle',
  messageKey: 'window.uploadError',
};

/**
 * Сервер отказался удалять вложение, снятое чипом из композера. Молчать здесь
 * нельзя: чип — единственный след файла на экране, и он уже исчез, так что
 * оставшееся вложение иначе не заметить.
 */
export const attachmentDeleteErrorNotice = (status) => ({
  icon: '⚠️',
  titleKey: 'errorModal.attachmentDeleteTitle',
  messageKey: 'errorModal.attachmentDeleteMessage',
  params: { suffix: suffixOf(status) },
});
