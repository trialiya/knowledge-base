/**
 * Module-level bridge letting deeply-nested components (DocLinkTooltip, mounted
 * both inside chat messages and inside KB markdown — several prop layers away
 * from App) trigger "open this path in the Files tab" navigation, without
 * threading an onNavigateToFile prop through every intermediate component
 * (Message/ChatWindow, SummarySection/MarkdownEditor/DetailModals/...).
 *
 * App.js is still the sole owner of navigation state (see useAppNavigation) —
 * it just registers its `openFilePath` here on mount. Same pattern as
 * useDocPreview's module cache: a plain module-scoped singleton, not React
 * context, since the producer (App) and consumers (DocLinkTooltip instances)
 * don't share a convenient common ancestor to pass a prop through.
 */
let navigator = null;

export function registerFileNavigator(fn) {
  navigator = fn;
}

export function navigateToFile(path) {
  navigator?.(path);
}
