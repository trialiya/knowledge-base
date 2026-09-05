// Фикстура блока «изменённые файлы» под последним ответом: у каждой строки своя кнопка отката,
// у уже откаченного файла — отметка вместо неё. Кейс подобран по тому, чем строки отличаются
// на экране: правка против создания, ещё откатываемый против уже откаченного.

const change = (name, operation, path, additions, deletions) => ({
  name,
  status: 'OK',
  resultMeta: { path, operation, additions, deletions, diff: `@@ -1 +1,${additions} @@` },
});

/** Последний ответ правил два файла и создал третий; один из правленных уже откачен. */
export const lastAnswer = {
  project: 'kb',
  conversationId: 'chat-1',
  canRevert: true,
  revertedPaths: new Set(['frontend/src/i18n/locales/ru/chat.json']),
  toolCalls: [
    change('editFile', 'edit', 'frontend/src/components/chatPanel/messages/FileChangeBlock.jsx', 41, 17),
    change('editFile', 'edit', 'frontend/src/i18n/locales/ru/chat.json', 6, 3),
    change('createFile', 'create', 'frontend/tests/visual/fixtures/fileChangeBlock.js', 24, 0),
  ],
};
