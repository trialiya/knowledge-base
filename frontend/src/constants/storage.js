/** localStorage: id последнего открытого чата. */
export const STORAGE_KEY_ACTIVE_CHAT = 'chat_activeId';

/** localStorage: модель последнего отправленного сообщения. */
export const STORAGE_KEY_LAST_MODEL = 'chat_lastModel';

/**
 * Псевдо-id черновика нового чата. Реальный UUID появляется только при
 * отправке первого сообщения — до этого бэк ничего о чате не знает.
 */
export const DRAFT_CHAT_ID = 'new';
