// Фикстуры визуальных кейсов панели «Репозиторий» в чате: вкладка, окна коммита
// и push, карточка вывода. Состояния подобраны по тому, чем они отличаются на
// экране, а не по тому, чем отличаются данные: чистое дерево против грязного,
// команда доступная против объяснённой, успех против отказа.

const branch = {
  current: 'claude/git-commands-user',
  detached: false,
  unborn: false,
  upstream: 'origin/claude/git-commands-user',
  ahead: 2,
  behind: 0,
  branches: ['main', 'claude/git-commands-user', 'feature/attachments'],
  dirty: true,
  merging: false,
  conflicts: [],
};

// Из чата запускаются ровно две команды; остальное живёт в панели «Файлы».
const commands = {
  commit: () => Promise.resolve(),
  push: () => Promise.resolve(),
  dismissFailure: () => {},
  project: 'kb',
  refreshToken: 0,
};

/** Обычное состояние: ветка впереди origin, три файла не сохранено. */
export const repoTabIdle = {
  git: {
    ...commands,
    loading: false,
    disabled: false,
    status: branch,
    capabilities: { project: 'kb', available: true, commands: true, push: true },
    changes: [
      { status: 'M', path: 'frontend/src/components/chatPanel/git/ChatRepoPanel.jsx' },
      { status: 'M', path: 'frontend/src/i18n/locales/ru/chat.json' },
      { status: 'A', path: 'frontend/src/components/chatPanel/git/gitOutputCard.css' },
    ],
    last: { command: 'commit', ok: true, at: 0 },
  },
  onOpenCommit: () => {},
  onOpenPush: () => {},
};

/**
 * Правка на пол-репозитория — обычный ответ ассистента. Вкладка показывает
 * первые четыре файла и считает остальные ссылкой: полсотни строк в панели
 * шириной 320px отвечают на вопрос «что наменяли» хуже, чем счётчик.
 */
export const repoTabManyChanges = {
  ...repoTabIdle,
  git: {
    ...repoTabIdle.git,
    changes: [
      { status: 'M', path: 'backend/src/main/java/io/github/trialiya/kb/service/file/git/GitService.java', additions: 18, deletions: 4 },
      { status: 'A', path: 'backend/src/main/java/io/github/trialiya/kb/service/file/git/RankingWeights.java', additions: 34, deletions: 0 },
      { status: 'A', path: 'backend/src/test/java/io/github/trialiya/kb/service/file/git/RankingTest.java', additions: 42, deletions: 0 },
      { status: 'M', path: 'backend/src/main/resources/application.yml', additions: 3, deletions: 1 },
      { status: 'A', path: 'backend/src/main/resources/db/migration/V27__ranking.sql', additions: 11, deletions: 0 },
      { status: 'A', path: 'backend/src/main/resources/db/migration-h2/V27__ranking.sql', additions: 11, deletions: 0 },
      { status: 'M', path: 'docs/проект/архитектура-и-реализация-поиска.md', additions: 26, deletions: 9 },
      { status: 'M', path: 'frontend/src/i18n/locales/ru/search.json', additions: 4, deletions: 0 },
      { status: 'M', path: 'frontend/src/i18n/locales/en/search.json', additions: 4, deletions: 0 },
      { status: 'U', path: 'build/reports/pmd/main.html', additions: 0, deletions: 0 },
    ],
    last: null,
  },
};

// Причина запрета едет вместе с ним: их три (модель работает, идёт другая
// команда, чат ещё черновик), и без неё ни подсказка вкладки, ни плашка модалки
// не появляются вовсе — см. `useChatGit.disabledReason`.
/** Модель работает: единственная кнопка вкладки выключена. */
export const repoTabBusy = {
  ...repoTabIdle,
  git: { ...repoTabIdle.git, disabled: true, disabledReason: 'busy', last: null },
};

/**
 * Незавершённый merge — единственное состояние, о котором вкладка говорит без
 * просьбы, и единственное, ради которого на самой вкладке горит точка.
 */
export const repoTabMerging = {
  ...repoTabIdle,
  git: {
    ...repoTabIdle.git,
    status: { ...branch, merging: true, conflicts: ['backend/build.gradle', 'settings.gradle'] },
    // Конфликтные файлы приезжают обычной «M»: буква у ряда — это буква из
    // `git status`, а про конфликт говорит отдельное поле `conflicts` и полоса
    // над списком. «U» у бэкенда значит «не отслеживается» (UNTRACKED_STATUS).
    changes: [
      { status: 'M', path: 'backend/build.gradle' },
      { status: 'M', path: 'settings.gradle' },
    ],
    last: { command: 'pull', ok: false, at: 0 },
  },
};

/**
 * Окно коммита на грязном дереве: слева выбор файлов, справа патч первой строки,
 * внизу описание. Список тот же, что у `repoTabManyChanges`, — окно как раз и
 * есть место, где полсотни строк имеют смысл.
 */
export const commitDialogDirty = {
  git: repoTabManyChanges.git,
  onClose: () => {},
};

/**
 * Патч, который отдаёт стенд на запрос diff'а. Стенд отвечает им на любой путь,
 * поэтому он про тот файл, который окно открывает первым (первый по имени).
 */
export const commitDialogPatch = [
  {
    status: 'M',
    path: 'docs/проект/архитектура-и-реализация-поиска.md',
    additions: 26,
    deletions: 9,
    patchHeader:
      'diff --git a/docs/проект/архитектура-и-реализация-поиска.md b/docs/проект/архитектура-и-реализация-поиска.md\nindex 1a2b3c4..5d6e7f8 100644',
    patch:
      '@@ -46,7 +46,9 @@ ## Ранжирование\n' +
      ' Итоговая позиция — сумма ключевого и семантического попадания.\n' +
      '-Совпадения в заголовке и в тексте весят одинаково.\n' +
      '+Совпадение в заголовке весит больше: множитель задаётся в `kb.search.title-weight`.\n' +
      '+Значение по умолчанию — 1.6; ноль отключает надбавку.\n',
  },
];

/** Окно коммита, пока модель работает: причина названа один раз на всё окно. */
export const commitDialogBusy = {
  git: { ...repoTabManyChanges.git, disabled: true, disabledReason: 'busy' },
  onClose: () => {},
};

/** Окно push: перед отправкой видно, что именно уедет. */
export const pushDialogAhead = {
  git: repoTabIdle.git,
  onClose: () => {},
};

/** Коммиты, которые стенд отдаёт окну push. */
export const pushDialogCommits = [
  {
    hash: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
    shortHash: 'a1b2c3d',
    author: 'Claude',
    email: 'noreply@anthropic.com',
    date: '2026-09-04T09:12:00+03:00',
    message: 'Ранжирование поиска: вес заголовка',
  },
  {
    hash: 'b2c3d4e5f60718293a4b5c6d7e8f901234567890',
    shortHash: 'b2c3d4e',
    author: 'Иван Петров',
    email: 'ivan@example.com',
    date: '2026-09-03T18:40:00+03:00',
    message: 'Миграции для обеих баз и фикстура',
  },
];

/** Ветка, которую ещё ни разу не отправляли: push её создаст. */
export const pushDialogNewBranch = {
  git: {
    ...repoTabIdle.git,
    status: { ...branch, upstream: null, ahead: 0 },
  },
  onClose: () => {},
};

/** Удачная команда в ленте: свёрнута до одной строки. */
export const outputCardOk = {
  event: {
    command: 'pull',
    project: 'kb',
    ok: true,
    output: 'Updating a1b2c3d..e4f5a6b\nFast-forward\n 12 files changed, 340 insertions(+), 71 deletions(-)',
    branch: 'main',
  },
};

/** Отказ: развёрнут сам, потому что ради него к карточке и возвращаются. */
export const outputCardRefused = {
  event: {
    command: 'push',
    project: 'kb',
    ok: false,
    output:
      'To github.com:trialiya/knowledge-base.git\n ! [remote rejected] main -> main (pre-receive hook declined)\nerror: failed to push some refs',
    branch: null,
  },
};

/** Молчаливый успех: для нескольких git-команд это обычный исход. */
export const outputCardSilent = {
  event: { command: 'fetch', project: 'kb', ok: true, output: '', branch: 'main' },
};
