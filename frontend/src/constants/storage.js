/** localStorage: id последнего открытого чата. */
export const STORAGE_KEY_ACTIVE_CHAT = 'chat_activeId';

/** localStorage: модель последнего отправленного сообщения. */
export const STORAGE_KEY_LAST_MODEL = 'chat_lastModel';

/** localStorage: режим последнего отправленного сообщения ('' — без режима). */
export const STORAGE_KEY_LAST_MODE = 'chat_lastMode';

/** Проект последней отправки — им стартует новый чат (см. lastChoiceStore). */
export const STORAGE_KEY_LAST_PROJECT = 'chat_lastProject';

/**
 * localStorage: неотправленные черновики ввода по чатам — JSON-объект
 * `{ [chatId]: text }`. Позволяет свободно переключаться между чатами, не теряя
 * набранный, но ещё не отправленный текст (в т.ч. после перезагрузки страницы).
 */
export const STORAGE_KEY_CHAT_DRAFTS = 'chat_drafts';

/**
 * localStorage: вложения, отложенные к отправке, по чатам — JSON-объект
 * `{ [chatId]: [{ kind, ref, label }] }`. Часть того же черновика, что и текст:
 * файл уже загружен в чат, откладывается только решение приложить его к
 * следующему сообщению (см. chatDrafts.js).
 */
export const STORAGE_KEY_CHAT_STAGED = 'chat_stagedContext';

/**
 * localStorage: состояние боковых панелей рабочей области по разделам —
 * JSON-объект `{ [view]: { leftCollapsed, rightTab } }`. Источник правды для
 * ТЕКУЩЕГО раздела — URL; здесь хранится раскладка остальных, чтобы она не
 * терялась при переключении вкладок. См. panelState.js.
 */
export const STORAGE_KEY_PANELS = 'ui_panels';

/**
 * localStorage: ширина левой панели в пикселях — одна на все разделы (см.
 * common/useLeftPanelWidth.js). Не в URL: ссылкой делятся раскладкой панелей,
 * а ширина — личная настройка рабочего места.
 */
export const STORAGE_KEY_LEFT_WIDTH = 'ui_leftWidth';

/**
 * Псевдо-id черновика нового чата. Реальный UUID появляется только при
 * отправке первого сообщения — до этого бэк ничего о чате не знает.
 */
export const DRAFT_CHAT_ID = 'new';
