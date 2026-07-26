/**
 * Фикстуры для шапки активного чата (components/chatPanel/ChatHeader.jsx).
 *
 * Форма объекта — та же, что приходит из GET /api/chats (см. ChatInfo.jsx:
 * id, title, createdAt, model, aiTopic). Сама шапка читает только id и title,
 * остальное лежит здесь, чтобы фикстуру можно было отдать и в правую панель
 * («Инфо»), не сочиняя её заново.
 *
 * Даты статические: снимок не должен меняться от прогона к прогону.
 */

/**
 * Чат с длинным, но помещающимся в строку заголовком — базовое состояние шапки.
 * Заголовок взят из реального разговора (он же в db/sample-data.sql), id
 * синтетический: реальные id из засеянной базы в фикстуры не тащим.
 */
export const activeChat = {
  id: 'chat-1',
  title: 'История коммитов backend/build.gradle',
  createdAt: '2026-07-18T18:00:00.000Z',
  updatedAt: '2026-07-18T18:05:00.000Z',
  model: 'default',
  aiTopic: null,
};

/** Пропсы шапки целиком: поиск доступен, find-бар закрыт. */
export const activeChatProps = {
  chat: activeChat,
  canSearch: true,
  searchOpen: false,
};
