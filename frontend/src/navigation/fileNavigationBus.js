import { FILE_TAB } from '@/constants/fileTabs';

/**
 * Module-level bridge letting deeply-nested components (the file and commit
 * links of rendered markdown — FileLink, CommitLink — and CommitHashLink, mounted
 * inside chat messages, KB markdown and panels, several prop layers away from
 * App) trigger "open this path / commit in the Files tab" navigation, without
 * threading an onNavigateToFile prop through every intermediate component
 * (Message/ChatWindow, MarkdownEditor/DetailModals/...).
 *
 * App.js is still the sole owner of navigation state (see useAppNavigation) —
 * it just registers its `openFilePath` here on mount. Same pattern as
 * useDocPreview's module cache: a plain module-scoped singleton, not React
 * context, since the producer (App) and consumers (the link instances)
 * don't share a convenient common ancestor to pass a prop through.
 */
let navigator = null;

/**
 * Регистрирует обработчик перехода. Возвращает функцию отписки — её отдают из
 * эффекта, чтобы модуль не держал замыкание размонтированного компонента
 * (в тестах и под StrictMode это ещё и лишний, уже мёртвый обработчик).
 */
export function registerFileNavigator(fn) {
  navigator = fn;
  return () => {
    // Проверка нужна на случай, если кто-то успел зарегистрироваться после нас:
    // отписка обязана снимать только свой обработчик, а не чужой.
    if (navigator === fn) navigator = null;
  };
}

/**
 * @param project репозиторий пути; переход по ссылке из чата обязан открыть файл
 *   ИМЕННО в том проекте, который назвала ссылка, переключив панель. Не назван —
 *   дефолтный (так выглядит любая ссылка, написанная до появления проектов).
 * @param options `{ changes: true }` — открыть левый блок в режиме «Изменения»:
 *   так уходят ссылки из вкладки «Репозиторий», которые ведут именно к
 *   незакоммиченному, а не к файлу в дереве. Не передан — режим не трогаем.
 *   `{ rev }` — открыть файл в снимке этой ревизии (ссылка на файл в коммите).
 *   `{ lines }` — выделить строки (`42-45`) и прокрутить к ним: так ведёт ячейка
 *   blame к ханку в снимке его коммита; `{ backLines }` — строки открытого
 *   сейчас файла, к которым вернёт «Назад» (см. navStore.openFilePath).
 */
export function navigateToFile(path, project, options) {
  navigator?.(path, project, options);
}

/**
 * Открыть коммит так, как его показывает панель «Файлы»: снимок ревизии, слева —
 * изменённые им файлы, справа — вкладка «Коммит». Тот же адрес строит
 * urlScheme.commitUrl — для Ctrl+клика по ссылке.
 */
export function navigateToCommit(hash, project) {
  navigator?.('', project, { rev: hash, changes: true, right: FILE_TAB.COMMIT });
}
