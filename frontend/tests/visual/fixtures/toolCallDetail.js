/**
 * Фикстуры ответа GET /api/chats/{id}/tool-calls?callId=… (ChatController →
 * ChatMemoryService.findToolCallDetail → ToolCallDetail).
 *
 * На них строится модалка деталей вызова инструмента: `resultText` — дословно
 * то, что ушло модели, то есть JSON возвращённого DTO. Формы ниже повторяют
 * настоящие DTO бэкенда (GitFileContent, DocumentNode, AttachmentContext),
 * данные синтетические и укорочены до нескольких строк.
 *
 * В db/sample-data.sql инструментов, возвращающих текст, нет вовсе (там
 * getCommitDiff, getTreeSkeleton, createDocument и recordChatInsights),
 * поэтому режим «Обзор» проверяется этими фикстурами, а не засеянным чатом.
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

/** Форма без обзора: переключателя режимов нет, показывается только JSON. */
export const commitLogCall = {
  name: 'getCommitLog',
  argumentsRaw: JSON.stringify({ limit: 2 }),
  status: 'OK',
  error: null,
  resultText: JSON.stringify([
    {
      hash: '8547d567e4c524805f74b0a523be2a8ec3892c1e',
      shortHash: '8547d56',
      author: 'trialiya',
      email: 'trialiya@example.org',
      date: '2026-07-13T02:41:21+03:00',
      message: 'Replace git subprocess calls with JGit for in-process operations',
      files: null,
    },
  ]),
  resultMeta: null,
  createdAt: '2026-08-14T10:16:20',
};
