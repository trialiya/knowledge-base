/**
 * Module-level bridge letting deeply-nested components (DocLinkTooltip, mounted
 * both inside chat messages and inside KB markdown — several prop layers away
 * from App) trigger "open this path in the Files tab" navigation, without
 * threading an onNavigateToFile prop through every intermediate component
 * (Message/ChatWindow, MarkdownEditor/DetailModals/...).
 *
 * App.js is still the sole owner of navigation state (see useAppNavigation) —
 * it just registers its `openFilePath` here on mount. Same pattern as
 * useDocPreview's module cache: a plain module-scoped singleton, not React
 * context, since the producer (App) and consumers (DocLinkTooltip instances)
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
 */
export function navigateToFile(path, project, options) {
  navigator?.(path, project, options);
}
