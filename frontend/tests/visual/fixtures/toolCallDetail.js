/**
 * Фикстуры ответа GET /api/chats/{id}/tool-calls?callId=… (ChatController →
 * ToolCallService.findToolCallDetail → ToolCallDetail).
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
    // `toShallowNode` заполняет children всегда, поэтому у документа с
    // вложенными они в ответе есть. Держим их в фикстуре: без них проверка
    // мимо самого частого вида документа и проходила.
    children: [
      { id: 31, title: 'Слои', type: 'document', parentId: 12, version: 1, hasChildren: false },
      { id: 32, title: 'Хранилище', type: 'document', parentId: 12, version: 2, hasChildren: true },
    ],
    hasChildren: true,
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

/**
 * Один названный коммит: `getCommitDiff` по хешу отдаёт его вместе с телом
 * сообщения — в сэмпле такого вызова нет, там оба вызова без тела.
 */
export const commitDiffCall = {
  name: 'getCommitDiff',
  argumentsRaw: JSON.stringify({ commitHashes: '38e5ba2', includePatch: true }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      hash: '38e5ba2c6941bf43815588d2dbbdb1d5be9590ce',
      shortHash: '38e5ba2',
      author: 'Ivan Petrov',
      email: 'ivan@example.com',
      date: '2026-06-17T23:58:42+03:00',
      message: 'Черновик чата переживает переключение на другой чат и перезагрузку',
      body:
        'Состояние черновика уехало в localStorage: в state оно жило до первого\n' +
        'размонтирования, а размонтируется центр на каждом переключении чата.\n\n' +
        'Ключ — id чата, поэтому черновики соседних чатов друг друга не затирают.',
      files: [
        {
          status: 'M',
          path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
          oldPath: null,
          additions: 3,
          deletions: 1,
          patch: PATCH_EDIT,
        },
      ],
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-16T09:06:31',
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

/** Оглавление документа: вложенность по уровню заголовка, преамбула — корень. */
export const documentOutlineCall = {
  name: 'getDocumentOutline',
  argumentsRaw: JSON.stringify({ documentId: 76 }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    id: 76,
    title: 'Хронология изменений backend/build.gradle',
    descriptionVersion: 4,
    sections: [
      { path: '', level: 0, title: '', chars: 210, subsections: 0 },
      { path: '1. Начальный сетап', level: 2, title: '1. Начальный сетап', chars: 640, subsections: 0 },
      { path: '2. Семантический поиск', level: 2, title: '2. Семантический поиск', chars: 380, subsections: 2 },
      {
        path: '2. Семантический поиск > Зависимости',
        level: 3,
        title: 'Зависимости',
        chars: 180,
        subsections: 0,
      },
      { path: '2. Семантический поиск > Миграции', level: 3, title: 'Миграции', chars: 120, subsections: 0 },
      { path: '9. Tree-sitter для file outline', level: 2, title: '9. Tree-sitter для file outline', chars: 520, subsections: 0 },
    ],
  }),
  resultMeta: null,
  createdAt: '2026-08-16T12:02:11',
};

/** Обзор файла: вложенность по диапазону строк — метод внутри класса. */
export const fileOutlineCall = {
  name: 'getFileOutline',
  argumentsRaw: JSON.stringify({ path: 'backend/src/main/java/io/github/trialiya/kb/service/GitService.java' }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    path: 'backend/src/main/java/io/github/trialiya/kb/service/GitService.java',
    language: 'java',
    lineCount: 742,
    parser: 'tree-sitter',
    symbols: [
      { kind: 'import', name: 'org.eclipse.jgit.api.Git', signature: null, startLine: 8, endLine: 8 },
      { kind: 'import', name: 'java.util.List', signature: null, startLine: 12, endLine: 12 },
      { kind: 'class', name: 'GitService', signature: 'public class GitService', startLine: 40, endLine: 730 },
      { kind: 'field', name: 'log', signature: 'private static final Logger log', startLine: 42, endLine: 42 },
      {
        kind: 'method',
        name: 'readFile',
        signature: 'public GitFileContent readFile(String path, Integer fromLine, Integer toLine)',
        startLine: 120,
        endLine: 186,
      },
      {
        kind: 'method',
        name: 'grepContent',
        signature: 'public List<GitGrepMatch> grepContent(String pattern, int contextLines, int limit)',
        startLine: 586,
        endLine: 640,
      },
      { kind: 'record', name: 'ParsedLine', signature: null, startLine: 731, endLine: 731 },
    ],
  }),
  resultMeta: null,
  createdAt: '2026-08-16T12:04:52',
};

/** Совпадения поиска: разметка `:N:` / `-N-` внутри текста блока. */
export const grepCall = {
  name: 'grepContent',
  argumentsRaw: JSON.stringify({ pattern: 'ToolCallResponseItem', contextLines: 2, limit: 20 }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      path: 'backend/src/main/java/io/github/trialiya/kb/model/git/dto/GitFileNode.java',
      matchLine: 16,
      text:
        '-14-  */\n' +
        '-15- public record GitFileNode(String path, String name, FileEntryType type, @Nullable Long size)\n' +
        ':16:         implements ToolCallResponseItem, ToolCallResultMetaProvider {\n' +
        '-17-\n' +
        '-18-     @Override\n',
    },
    {
      path: 'backend/src/main/java/io/github/trialiya/kb/model/git/dto/GitGrepMatch.java',
      matchLine: 29,
      text:
        '-27- public record GitGrepMatch(String path, int matchLine, String text)\n' +
        ':29:         implements ToolCallResponseItem, ToolCallResultMetaProvider {\n' +
        '-30-\n',
    },
    {
      path: 'backend/src/main/java/io/github/trialiya/kb/model/doc/dto/DocumentShort.java',
      matchLine: 24,
      text:
        '-22-         boolean summaryStale,\n' +
        '-23-         @Nullable Integer summarySourceVersion)\n' +
        ':24:         implements ToolCallResponseItem, ToolCallResultMetaProvider {\n',
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-16T12:07:19',
};

/**
 * Документная мутация: карточка правки со ссылкой в историю версий. В
 * аргументах — весь markdown документа, то есть тот самый случай, ради которого
 * длинное значение показывается блоком, а не строкой с экранированными `\n`.
 */
export const docMutationCall = {
  name: 'createDocument',
  argumentsRaw: JSON.stringify({ title: 'анализ', type: 'folder', parentId: null, description: DOC_MARKDOWN }),
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

/** Прогон скрипта: статистика, лог, прочитанные пути и две правки с diff'ами. */
export const scriptRunCall = {
  name: 'runScript',
  argumentsRaw: JSON.stringify({
    script: "const files = kb.glob('frontend/src/**/*.css');\nfor (const f of files) kb.log(f);\nreturn files.length;",
  }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    value: 2,
    log: [
      'frontend/src/components/chatPanel/styles/diff.css',
      'frontend/src/components/chatPanel/styles/tool-diff.css',
      'переписано правил: 2',
    ],
    stats: { filesRead: 12, bytesRead: 262144, calls: 31, filesEdited: 2, elapsedMs: 412 },
    error: null,
    filesRead: [
      'frontend/src/components/chatPanel/ChatCenter.jsx',
      'frontend/src/components/chatPanel/styles/diff.css',
      'frontend/src/legacy/toolbar.css',
    ],
    edits: [
      {
        operation: 'edit',
        path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
        additions: 3,
        deletions: 1,
        lineCount: 142,
        diff: PATCH_EDIT,
      },
      {
        operation: 'edit',
        path: 'frontend/src/legacy/toolbar.css',
        additions: 0,
        deletions: 4,
        lineCount: 0,
        diff: PATCH_DELETE,
      },
    ],
  }),
  resultMeta: null,
  createdAt: '2026-08-16T13:10:38',
};

/** Упавший прогон: правок нет вовсе, зато видно, докуда скрипт дошёл. */
export const scriptFailedCall = {
  name: 'runScript',
  argumentsRaw: JSON.stringify({ script: "const all = kb.glob('**/*');\nreturn all.map(kb.read).length;" }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    value: null,
    log: ['обход начат от корня репозитория', 'прочитано 200 файлов'],
    stats: { filesRead: 200, bytesRead: 5242880, calls: 200, filesEdited: 0, elapsedMs: 8140 },
    error: { kind: 'BUDGET', message: 'превышен лимит kb.script.limits.files: 200', line: 2 },
    filesRead: ['backend/build.gradle', 'backend/settings.gradle'],
    edits: [],
  }),
  resultMeta: null,
  createdAt: '2026-08-16T13:12:04',
};

/**
 * MCP-инструмент произвольной формы: вида для неё нет и быть не может —
 * переключателя режимов не будет вовсе, показывается только JSON.
 */
export const mcpCall = {
  name: 'mcp__tracker__get_queue',
  argumentsRaw: JSON.stringify({ queue: 'build-web' }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({ ok: true, queue: 'build-web', queued: 3, workers: 2 }),
  resultMeta: null,
  createdAt: '2026-08-16T13:14:51',
};

/**
 * Поиск по коду суб-агентом: единственный инструмент, который сам ходит в модель, — и потому
 * единственный, у которого в деталях есть строка цены. Числа в resultMeta намеренно разошлись:
 * контекст суб-агента 18.4k при total input 41.3k за четыре обращения — это и есть та разница,
 * ради которой цену вообще показывают. Модели их не отдают (@JsonIgnore в SearchAgentResult), поэтому в
 * resultText, то есть в JSON-режиме модалки, их нет.
 */
export const searchCodebaseCall = {
  name: 'searchCodebase',
  argumentsRaw: JSON.stringify({ task: 'где проверяется авторизация запроса', scope: 'backend' }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify({
    project: 'kb',
    report:
      'Проверка авторизации собрана в одном фильтре и одном конфиге:\n\n' +
      '- `SecurityConfig.java:42` — HTTP Basic на всё, кроме `/actuator/health`\n' +
      '- `SecurityConfig.java:61` — единственный пользователь берётся из `kb.auth`\n' +
      '- `SpaForwardController.java:28` — форвард SPA идёт ПОСЛЕ фильтра, поэтому неавторизованный\n' +
      '  запрос не получает index.html вместо 401',
    complete: true,
    iterations: 4,
  }),
  resultMeta: {
    project: 'kb',
    complete: true,
    iterations: 4,
    durationMs: 18420,
    reportChars: 380,
    model: 'gpt-5-mini',
    usage: {
      contextTokens: 18400,
      toolTokens: 12100,
      outputTokens: 870,
      promptTokens: 41260,
      cacheReadTokens: 29800,
      cacheWriteTokens: 1180,
      modelCalls: 4,
    },
  },
  createdAt: '2026-08-28T11:20:00',
};
