/**
 * Лента плашек вызовов инструментов под ответом ассистента: список в том виде,
 * в каком его держит пузырь сообщения (проекция toolCallOf, см.
 * chatPanel/run/runMessageOps.js), плюс тело ответа
 * GET /api/chats/{id}/tool-calls для модалки, которую с плашки открывают.
 *
 * Кейс — про ленту, которая перестраивается под идущий ответ: `next` это
 * вызов, который модель делает того же инструмента, пока читают детали
 * предыдущего. Такого состояния у живого приложения не попросишь: оно длится
 * ровно один шаг tool-цикла.
 */

/** Правка файла, за ней — вторая правка того же инструмента: 1 плашка → группа «×2». */
export const editFileTwice = {
  calls: [
    {
      name: 'editFile',
      callId: 'call-1',
      callIndex: 1,
      status: 'OK',
      hasDetails: true,
      arguments: {
        path: 'frontend/src/components/chatPanel/ChatCenter.jsx',
        oldText: "  const [draft, setDraft] = useState('');",
      },
      resultGist: 'edit · +3 −1 · ChatCenter.jsx',
    },
  ],
  next: {
    name: 'editFile',
    callId: 'call-2',
    callIndex: 2,
    status: 'STARTED',
    hasDetails: true,
    arguments: {
      path: 'frontend/src/components/chatPanel/messages/ToolCallNotifications.jsx',
      oldText: '  const [showDetail, setShowDetail] = useState(false);',
    },
  },
  detail: {
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
      diff: [
        '@@ -18,7 +18,9 @@',
        "-  const [draft, setDraft] = useState('');",
        '+  const [draft, setDraft] = useState(() => readDraft(chatId));',
        '+',
        '+  useEffect(() => saveDraft(chatId, draft), [chatId, draft]);',
      ].join('\n'),
    }),
    resultMeta: null,
    createdAt: '2026-08-16T09:02:14',
  },
};

/**
 * Три исхода вызова рядом: успех, провал и «исход не сохранён». Кейс про то, чем они
 * отличаются на вид — цветом полосы, значком и тем, что показано под аргументами.
 */
export const callOutcomes = [
  {
    name: 'getFileContent',
    callId: 'call-1',
    status: 'OK',
    hasDetails: true,
    arguments: { path: 'backend/src/main/java/io/github/trialiya/kb/functions/GitFunction.java' },
    resultGist: 'java · 323 строки · 11.5 КБ',
  },
  {
    name: 'editFile',
    callId: 'call-2',
    status: 'ERROR',
    hasDetails: true,
    arguments: { path: 'docs/проект/архитектура.md', oldText: '## Слои' },
    error: 'oldText not found in docs/проект/архитектура.md: 0 matches, expected exactly 1',
  },
  {
    name: 'searchDocuments',
    callId: 'call-3',
    status: 'UNKNOWN',
    hasDetails: false,
    arguments: { query: 'кэш эмбеддингов' },
    resultGist: '[{"id":42,"title":"Кэш эмбеддингов","score":0.…',
  },
];
