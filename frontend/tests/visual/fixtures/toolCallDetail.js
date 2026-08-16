/**
 * Фикстуры ответа GET /api/chats/{id}/tool-calls?callId=… (ChatController →
 * ChatMemoryService.findToolCallDetail → ToolCallDetail).
 *
 * На них строится модалка деталей вызова инструмента: `resultText` — дословно
 * то, что ушло модели, то есть JSON возвращённого DTO. Формы ниже повторяют
 * настоящие DTO бэкенда (GitFileContent, DocumentNode, AttachmentContext),
 * данные синтетические и укорочены до нескольких строк.
 *
 * Фикстуры — только для форм, которых в db/sample-data.sql нет: там есть
 * getFileContent, getDocumentSection и два getCommitDiff, и основной путь
 * проверяется живыми кликами по засеянному чату (см. cases.yaml).
 */

const JAVA_SOURCE = `    @Tool(
            description =
                    "Возвращает содержимое файла из репозитория. Диапазон строк"
                        + " задаётся fromLine/toLine — целиком файл читать не нужно.")
    public GitFileContent getFileContent(
            @ToolParam(description = "Относительный путь от корня репозитория") String path,
            @ToolParam(description = "Первая строка, 1-based", required = false) Integer fromLine,
            @ToolParam(description = "Последняя строка, включительно", required = false)
                    Integer toLine) {
        final String resolved = ToolArgs.requireText("path", path);
        return gitService.readFile(resolved, fromLine, toLine);
    }`;

const DOC_MARKDOWN = `## Слои

Запрос проходит через четыре слоя, каждый следующий не знает о предыдущем:

| Слой | Пакет | Отвечает за |
| --- | --- | --- |
| Controller | \`controller\` | HTTP, валидация, коды ответа |
| Service | \`service\` | бизнес-логику и транзакции |
| AI | \`advisor\`, \`tools\` | вызовы модели и инструментов |
| Repository | \`repository\` | доступ к данным |

### Правила

- Контроллер не ходит в репозиторий напрямую.
- Инструмент модели — тонкая обёртка над сервисом, своей логики не несёт.`;

/** Содержимое файла диапазоном строк: номера смещены на fromLine, язык — java. */
export const fileContentCall = {
  name: 'getFileContent',
  argumentsRaw: JSON.stringify({
    path: 'backend/src/main/java/io/github/trialiya/kb/functions/GitFunction.java',
    fromLine: 212,
    toLine: 223,
  }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    path: 'backend/src/main/java/io/github/trialiya/kb/functions/GitFunction.java',
    content: JAVA_SOURCE,
    binary: false,
    sizeBytes: 11542,
    language: 'java',
    lineCount: 323,
    truncated: true,
    fromLine: 212,
    toLine: 223,
  }),
  resultMeta: null,
  createdAt: '2026-08-14T10:12:03',
};

/** Документ базы знаний: markdown, поэтому «Обзор» открывается рендером. */
export const documentCall = {
  name: 'getDocument',
  argumentsRaw: JSON.stringify({ id: 12 }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    id: 12,
    title: 'Архитектура',
    type: 'document',
    parentId: 1,
    version: 3,
    description: DOC_MARKDOWN,
    descriptionVersion: 7,
    hasChildren: false,
    system: false,
    summary: null,
    summaryStale: false,
  }),
  resultMeta: null,
  createdAt: '2026-08-14T10:13:41',
};

/** Два вложения за вызов — по блоку на файл, каждый со своей шапкой фактов. */
export const attachmentsCall = {
  name: 'getAttachmentContentByFileName',
  argumentsRaw: JSON.stringify({ fileNames: ['release-notes.md', 'limits.txt'] }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      id: 41,
      fileName: 'release-notes.md',
      content: '# 1.4.0\n\n- Поиск по вложениям\n- Оглавление документа для модели\n- Правка файлов рабочего дерева\n\n# 1.3.2\n\n- Исправлен экспорт при пустой папке',
    },
    {
      id: 42,
      fileName: 'limits.txt',
      content: 'files    = 200\nbytes    = 5 MiB\ncalls    = 400\ntimeout  = 30s\n\nПревышение любого из лимитов останавливает прогон\nи возвращает модели ScriptError с видом BUDGET.',
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-14T10:15:02',
};

const PATCH_EDIT = `diff --git a/frontend/src/components/chatPanel/ChatCenter.jsx b/frontend/src/components/chatPanel/ChatCenter.jsx
index 4b1c9e2..7d0af31 100644
--- a/frontend/src/components/chatPanel/ChatCenter.jsx
+++ b/frontend/src/components/chatPanel/ChatCenter.jsx
@@ -18,7 +18,9 @@ const ChatCenter = ({ messages, onSend }) => {
   const { t } = useTranslation('chat');
-  const [draft, setDraft] = useState('');
+  const [draft, setDraft] = useState(() => readDraft(chatId));
+  // Черновик переживает переключение чата — он в localStorage, не в state.
+  useDraftSync(chatId, draft);

   return (`;

const PATCH_DELETE = `diff --git a/frontend/src/legacy/toolbar.css b/frontend/src/legacy/toolbar.css
deleted file mode 100644
index 9ac41b8..0000000
--- a/frontend/src/legacy/toolbar.css
+++ /dev/null
@@ -1,4 +0,0 @@
-.legacy-toolbar {
-  display: flex;
-  gap: 4px;
-}`;

/** Одиночная правка файла: GitEditResult, статус выводится из operation. */
export const editFileCall = {
  name: 'editFile',
  argumentsRaw: JSON.stringify({
    path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
    oldText: "  const [draft, setDraft] = useState('');",
    newText: '  const [draft, setDraft] = useState(() => readDraft(chatId));',
  }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    operation: 'edit',
    path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
    additions: 3,
    deletions: 1,
    lineCount: 142,
    diff: PATCH_EDIT,
  }),
  resultMeta: null,
  createdAt: '2026-08-16T09:02:14',
};

/**
 * Несколько файлов за вызов: разные статусы, переименование со старым путём и
 * запись без патча — на ней видно, во что вырождается вид без diff'а.
 */
export const uncommittedChangesCall = {
  name: 'getUncommittedChanges',
  argumentsRaw: JSON.stringify({ includePatch: true }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      status: 'M',
      path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
      oldPath: null,
      additions: 3,
      deletions: 1,
      patch: PATCH_EDIT,
    },
    {
      status: 'D',
      path: 'frontend/src/legacy/toolbar.css',
      oldPath: null,
      additions: 0,
      deletions: 4,
      patch: PATCH_DELETE,
    },
    {
      status: 'R',
      path: 'frontend/src/components/chatPanel/diffRender.jsx',
      oldPath: 'frontend/src/components/chatPanel/DiffLines.jsx',
      additions: 12,
      deletions: 2,
      patch: null,
    },
    {
      status: 'A',
      path: 'frontend/src/components/chatPanel/styles/diff.css',
      oldPath: null,
      additions: 34,
      deletions: 0,
      patch: null,
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-16T09:04:47',
};

/** Выдача поиска: пояснение из snippet, хлебные крошки — полем в развороте. */
export const searchDocumentsCall = {
  name: 'searchDocuments',
  argumentsRaw: JSON.stringify({ query: 'слои приложения', limit: 3 }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      id: 12,
      title: 'Архитектура',
      snippet: 'Запрос проходит через четыре слоя: Controller → Service → AI → Repository.',
      updatedAt: '2026-08-12T10:24:11',
      summary: null,
      parentList: [
        { id: 1, title: 'Проект' },
        { id: 4, title: 'Разработка' },
      ],
    },
    {
      id: 31,
      title: 'Обзор чат-системы',
      snippet: 'Сегменты ответа, вызовы инструментов и то, как они попадают в chat_message.',
      updatedAt: '2026-08-03T18:02:40',
      summary: 'Как устроен чат: сегменты, инструменты, персистентность.',
      parentList: [{ id: 1, title: 'Проект' }],
    },
    {
      id: 44,
      title: 'Поиск — руководство пользователя',
      snippet: 'Семантический поиск ищет по смыслу, а не по вхождению слова.',
      updatedAt: '2026-07-29T09:15:00',
      summary: null,
      parentList: [
        { id: 1, title: 'Проект' },
        { id: 9, title: 'Возможности' },
      ],
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-16T11:20:05',
};

/** Вложения: заголовок из имени файла, пояснение из сводки, тип и размер — чипами. */
export const attachmentListCall = {
  name: 'getChatAttachments',
  argumentsRaw: JSON.stringify({}),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      id: 1,
      ownerType: 'chat',
      documentId: null,
      conversationId: 'c5dfa618-0ad2-4845-a976-ada46c50f9a4',
      fileName: 'gradle-build-error.log',
      contentType: 'text/plain',
      fileSize: 2048,
      summary: 'Сборка падает: не найден тулчейн Java 25.',
      sourceUrl: null,
      createdAt: '2026-07-18T20:58:02+03:00',
      updatedAt: '2026-07-18T20:58:02+03:00',
    },
    {
      id: 2,
      ownerType: 'chat',
      documentId: null,
      conversationId: 'c5dfa618-0ad2-4845-a976-ada46c50f9a4',
      fileName: 'схема-модулей.png',
      contentType: 'image/png',
      fileSize: 184320,
      summary: null,
      sourceUrl: null,
      createdAt: '2026-07-18T21:03:40+03:00',
      updatedAt: '2026-07-18T21:03:40+03:00',
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-16T11:22:31',
};

/** Скаляр: в засеянном чате recordChatInsights плашки не пишет, поэтому фикстура. */
export const insightsCall = {
  name: 'recordChatInsights',
  argumentsRaw: JSON.stringify({
    insights: 'Пользователь работает над backend/build.gradle и просит хронологию изменений.',
  }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify('Done'),
  resultMeta: null,
  createdAt: '2026-08-16T11:24:08',
};

/**
 * Форма, у которой вида ещё нет: переключателя режимов не будет вовсе,
 * показывается только JSON. Документная мутация — это форма F по
 * docs/todo/tool-результаты-отображение.md, её вид отдельным этапом.
 */
export const docMutationCall = {
  name: 'createDocument',
  argumentsRaw: JSON.stringify({ title: 'анализ', type: 'folder', parentId: null }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    id: 75,
    title: 'анализ',
    type: 'folder',
    parentId: null,
    version: 1,
    descriptionVersion: 1,
    updatedAt: '2026-07-18T21:00:55.850512',
    summaryStale: false,
    summarySourceVersion: null,
  }),
  resultMeta: null,
  createdAt: '2026-08-14T10:16:20',
};
