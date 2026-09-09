/**
 * Раздел «Поиск»: данные для карточек результата и для левой панели.
 *
 * Формы ответов — как у эндпоинтов (`GET /api/git/grep`,
 * `/api/documents/search/grouped`, `/api/chats/search/grouped`, см.
 * api-reference.md), но синтетические: пути, id и тексты придуманы.
 *
 * Даты и время в фикстурах без зоны: `toLocaleDateString`/`toLocaleTimeString`
 * читают их в зоне машины, поэтому подпись сообщения воспроизводима ровно в том
 * окружении, которому принадлежат эталоны (см. baselines/environment.json).
 */

/** Запрос, по которому «нашлись» все фикстуры ниже: по нему же идёт подсветка. */
export const query = 'grep';

/**
 * Файл с семью совпадениями: пять видно сразу, две последние — под «ещё N».
 * Ради этого их именно семь, а не две и не двадцать.
 */
export const fileEntry = {
  data: {
    total: 9,
    truncated: false,
    files: [
      {
        path: 'backend/src/main/java/io/github/trialiya/kb/service/file/git/GitGrepRunner.java',
        lines: [
          { line: 41, text: '    List<GitGrepMatch> grepContent(String pattern, @Nullable String pathGlob) {' },
          { line: 58, text: '        List<String> args = GitGrep.args(pattern, pathspec, regex, ctx, roots, null);' },
          { line: 72, text: '        if (exit > 1) throw new IllegalStateException("git grep exited " + exit);' },
          { line: 90, text: '        // git grep печатает <файл>:<строка>:<текст>, разбираем построчно' },
          { line: 104, text: '        return timedOut("git grep did not finish within " + timeout.toSeconds() + "s");' },
          { line: 131, text: '    /** Второй проход: неотслеживаемое, куда git grep сам не заходит. */' },
          { line: 152, text: '        log.debug("git grep: {} matches in {} files", matches.size(), files);' },
        ],
      },
    ],
  },
  error: null,
};

/**
 * Два документа: у первого совпадения из тела, разложенные по разделам — два в
 * одном и одно в другом, плюс строка до первого заголовка; второй найден по
 * смыслу и несёт только сниппет ранжирования, без номера строки и раздела.
 */
export const docEntry = {
  data: {
    total: 5,
    documents: [
      {
        id: 41,
        title: 'Поиск по содержимому репозитория',
        updatedAt: '2026-07-18T12:30:00',
        parentList: [
          { id: 1, title: 'Проект' },
          { id: 7, title: 'Анализ' },
        ],
        fragments: [
          { line: 3, sectionPath: '_preamble', text: 'Как grep ищет по репозиторию и где останавливается.' },
          {
            line: 88,
            sectionPath: 'Инструменты > grepContent',
            text: 'Инструмент grep ходит в рабочее дерево, а с ревизией — в снимок коммита.',
          },
          { line: 91, sectionPath: 'Инструменты > grepContent', text: 'Шаблон grep всегда регистронезависим.' },
          { line: 140, sectionPath: 'Пределы', text: 'Второй grep по неотслеживаемым идёт отдельным проходом.' },
        ],
      },
      {
        id: 42,
        title: 'Индексация репозитория',
        updatedAt: '2026-07-02T09:15:00',
        parentList: [{ id: 1, title: 'Проект' }],
        fragments: [{ line: null, sectionPath: null, text: 'Поиск по содержимому файлов и его пределы.' }],
      },
    ],
  },
  error: null,
};

/** Чат, совпавший и названием, и двумя сообщениями разных ролей. */
export const chatEntry = {
  data: {
    total: 3,
    truncated: false,
    chats: [
      {
        conversationId: 'c-41',
        topic: 'Почему git grep молчит на неотслеживаемых',
        updatedAt: '2026-07-18T20:59:00',
        titleMatched: true,
        messages: [
          {
            id: 811,
            role: 'USER',
            createdAt: '2026-07-18T20:41:00',
            fragments: ['…почему grep не находит файл, которого нет в индексе?…'],
          },
          {
            id: 812,
            role: 'ASSISTANT',
            createdAt: '2026-07-18T20:59:00',
            fragments: [
              '…git grep без --untracked ходит только по отслеживаемым…',
              '…добавь --untracked, и grep увидит новые файлы тоже…',
            ],
          },
        ],
      },
    ],
  },
  error: null,
};

/** Три формы результата разом: кейс про анатомию карточек, а не про одну категорию. */
export const resultCards = { query, files: fileEntry, docs: docEntry, chats: chatEntry };

/**
 * Четыре ответа центра, в которых результатов нет вовсе: запроса не ввели,
 * ничего не нашлось, фильтры отклонены (400) и поиск не уложился в срок (503).
 * Последний живому приложению на здешнем репозитории не показать — он и есть
 * причина снимать это стендом.
 */
export const emptyAndRefusal = {
  query,
  states: [
    { key: 'noQuery', query: '', entry: null },
    { key: 'nothing', entry: { data: { total: 0, truncated: false, files: [] }, error: null } },
    { key: 'badFilter', entry: { data: null, error: { status: 400 } } },
    { key: 'timeout', entry: { data: null, error: { status: 503 } } },
  ],
};

/**
 * Левая панель: категории со счётчиками и наборы фильтров всех трёх категорий
 * сразу. В приложении виден набор ровно одной — кейс как раз про то, чем они
 * различаются, поэтому стенд ставит их друг под другом.
 */
export const scopePanel = {
  counts: { files: 35, docs: 4, chats: 1 },
  filters: {
    path: 'backend/**/*.java',
    project: 'kb',
    projectOptions: [
      { id: 'kb', label: 'Knowledge Base' },
      { id: 'docs', label: 'Документация' },
    ],
    rev: 'v1.4.0',
    regex: true,
    // Стоит вместе с ревизией: галочка при ней выключена и снята, хотя в адресе
    // значение осталось — сняли ревизию, и она вернулась.
    untracked: true,
    mode: 'hybrid',
  },
};
