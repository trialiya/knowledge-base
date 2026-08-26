// Фикстуры визуальных кейсов панели «Репозиторий» в чате: вкладка, модалка
// команд и карточка вывода. Состояния подобраны по тому, чем они отличаются на
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

const commands = {
  fetch: () => {},
  pull: () => {},
  push: () => {},
  stashPush: () => {},
  stashPop: () => {},
  switchBranch: () => Promise.resolve(),
  commit: () => Promise.resolve(),
  abortMerge: () => {},
  dismissFailure: () => {},
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
  onOpenCommands: () => {},
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

/** Модалка на грязном дереве: коммит доступен, всё остальное объяснено. */
export const commandsModalDirty = {
  git: repoTabIdle.git,
  onClose: () => {},
};

/** Модалка после конфликтного pull: выход из merge — здесь же, где в него вошли. */
export const commandsModalMerging = {
  git: {
    ...repoTabMerging.git,
    failure: {
      command: 'pull',
      reason: 'CONFLICT (content): Merge conflict in backend/build.gradle\nAutomatic merge failed',
    },
  },
  onClose: () => {},
};

/** Модалка, пока модель работает: причина названа один раз на всю модалку. */
export const commandsModalBusy = {
  git: { ...repoTabIdle.git, disabled: true, disabledReason: 'busy' },
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
