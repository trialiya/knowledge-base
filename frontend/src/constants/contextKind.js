// Виды контекста, привязываемого к сообщению пользователя. Значения совпадают с
// backend-овским ContextItemKind — они же лежат в chat_message.meta, поэтому это
// часть формата хранения, а не только договорённость фронта с бэком.
export const CONTEXT_KIND = {
  ATTACHMENT: 'ATTACHMENT',
};
