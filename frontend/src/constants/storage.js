/** localStorage: id последнего открытого чата. */
export const STORAGE_KEY_ACTIVE_CHAT = 'chat_activeId';

/** localStorage: модель последнего отправленного сообщения. */
export const STORAGE_KEY_LAST_MODEL = 'chat_lastModel';

/** localStorage: режим последнего отправленного сообщения ('' — без режима). */
export const STORAGE_KEY_LAST_MODE = 'chat_lastMode';

/**
 * localStorage: неотправленные черновики ввода по чатам — JSON-объект
 * `{ [chatId]: text }`. Позволяет свободно переключаться между чатами, не теряя
 * набранный, но ещё не отправленный текст (в т.ч. после перезагрузки страницы).
 */
export const STORAGE_KEY_CHAT_DRAFTS = 'chat_drafts';

/**
 * Псевдо-id черновика нового чата. Реальный UUID появляется только при
 * отправке первого сообщения — до этого бэк ничего о чате не знает.
 */
export const DRAFT_CHAT_ID = 'new';
