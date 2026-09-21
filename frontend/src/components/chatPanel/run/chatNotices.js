// ─── Уведомления чата ────────────────────────────────────────────────────────
// Дескрипторы для useNotice: иконка плюс ключи перевода. Собраны в одном месте,
// потому что рисует их все один `<ErrorModal>` в ChatWindow, а поводы приходят
// из разных хуков — так видно сразу весь набор и не разъезжаются иконки.
//
// Ключи даны без префикса пространства имён: их резолвит `t` из ChatWindow,
// а он привязан к `chat`.

import { COMMAND_BLOCK } from './chatCommands';

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

/** `/compact` в чате, который ещё не начат: сжимать нечего, и заводить чат ради этого незачем. */
export const COMPACT_DRAFT_NOTICE = {
  icon: '🗜️',
  titleKey: 'compact.unavailableTitle',
  messageKey: 'compact.draftMessage',
};

/** `/script` в чате, который ещё не начат: ряд прогона писать некуда. */
export const SCRIPT_DRAFT_NOTICE = {
  icon: '⚙️',
  titleKey: 'script.unavailableTitle',
  messageKey: 'script.draftMessage',
};

/** `/script` без имени: запускать нечего, и сервер ответил бы тем же, только кругом. */
export const SCRIPT_NAME_NOTICE = {
  icon: '⚙️',
  titleKey: 'script.unavailableTitle',
  messageKey: 'script.noNameMessage',
};

/** Прогон отказал: имя, аргумент, файл на ветке — словами сервера. */
export const scriptFailedNotice = (message) => ({
  icon: '⚙️',
  titleKey: 'script.failedTitle',
  message,
});

/**
 * Отказ по причине, которую назвал `chatCommandBlock`. Композер пишет про ту же
 * причину своей короткой строкой над полем — словарь у них разный (в строку не
 * влезает абзац модалки), причина одна.
 */
export const COMMAND_BLOCK_NOTICE = {
  [COMMAND_BLOCK.RUNNING]: RUN_BUSY_NOTICE,
  [COMMAND_BLOCK.NOTHING_TO_COMPACT]: COMPACT_DRAFT_NOTICE,
  [COMMAND_BLOCK.NO_CHAT]: SCRIPT_DRAFT_NOTICE,
  [COMMAND_BLOCK.NO_SCRIPT_NAME]: SCRIPT_NAME_NOTICE,
};

/** `/compact` по контексту, который уже состоит из одной сводки (ответ 422). */
export const COMPACT_EMPTY_NOTICE = {
  icon: '🗜️',
  titleKey: 'compact.unavailableTitle',
  messageKey: 'compact.emptyMessage',
};

/**
 * Тот же 422, но у `/compact-1`: до сбережённого хода в контексте не осталось ничего сжимаемого.
 * Словами про «одну сводку» это не объяснить — сжимать нечего ровно потому, что последний ход и
 * есть весь разговор.
 */
export const COMPACT_KEEP_LAST_EMPTY_NOTICE = {
  icon: '🗜️',
  titleKey: 'compact.unavailableTitle',
  messageKey: 'compact.emptyKeepLastMessage',
};

/** Сжатие не удалось запустить (сбой самого запроса, не раунда). */
export const COMPACT_START_ERROR_NOTICE = {
  icon: '⚠️',
  titleKey: 'compact.errorTitle',
  messageKey: 'compact.startErrorMessage',
};

/** Сообщение, отправленное во время ответа, не приняли в очередь (сбой запроса). */
export const QUEUE_ERROR_NOTICE = {
  icon: '⚠️',
  titleKey: 'errorModal.queueTitle',
  messageKey: 'errorModal.queueMessage',
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
