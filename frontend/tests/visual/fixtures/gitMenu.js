// Фикстура выбора ветки в панели «Файлы»: репозиторий, в котором веток много и
// имена у них длинные. Живой репозиторий такого состояния по требованию не
// показывает — ветки для проверки пришлось бы заводить вручную, — а решает всё
// на экране именно оно: список меряется по самому длинному пункту.
//
// Имена не выдуманы «подлиннее», а взяты той формы, что встречается: общий
// префикс (`feature/`, `renovate/`) и расхождение в хвосте — то самое, из-за
// чего обрезанные пункты читаются одинаково и без подсказки не различаются.

const branches = [
  'chore/dependency-locking-refresh-after-spring-ai-bump',
  'claude/files-panel-branch-selection-4p3ucb',
  'docs/russian-product-documentation-restructure-part-two',
  'feature/chat-panel-git-commands-modal-and-output-card',
  // Единственная, что не влезает и в потолок ширины: без неё кейс показывал бы
  // список, где обрезать нечего, и молчал бы ровно о том, ради чего заведён.
  'feature/knowledge-base-attachments-preview-tooltip-rework-with-inline-editing-and-drag-drop',
  'feature/knowledge-base-attachments-preview-tooltip-rework',
  'fix/backend-git-service-non-ascii-paths-locale-fallback',
  'hotfix/files-panel-tree-horizontal-scroll-reset-regression',
  'main',
  'release/2026.08-documentation-sync-and-migration-cleanup',
  'renovate/org.springframework.boot-spring-boot-starter-web',
  'wip',
];

const noop = () => {};

/** Меню открыто на репозитории с двенадцатью ветками, десять из них — длинные. */
export const branchMenuLongNames = {
  status: {
    current: 'claude/files-panel-branch-selection-4p3ucb',
    detached: false,
    unborn: false,
    upstream: 'origin/claude/files-panel-branch-selection-4p3ucb',
    ahead: 2,
    behind: 1,
    branches,
    dirty: true,
    merging: false,
    conflicts: [],
  },
  capabilities: { project: 'default', available: true, commands: true, push: false },
  running: false,
  onFetch: noop,
  onAbortMerge: noop,
  commands: {
    onSwitch: noop,
    onCreateBranch: noop,
    onStashPush: noop,
    onStashPop: noop,
    onCommit: noop,
    onPull: noop,
    onPush: noop,
  },
};
