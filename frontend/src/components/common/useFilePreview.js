import gitApi from '../../api/gitApi';
import usePreviewCache, { createPreviewStore } from './usePreviewCache';

/**
 * Module-level store: path (string) → GitFileContent | 'error'. Unlike
 * useDocPreview, entries expire after STALE_MS — the repo can change from
 * outside the app (e.g. `git pull` run in a terminal), so a long-lived cache
 * would show stale content indefinitely. A short TTL keeps repeated hovers
 * instant while still picking up external changes within a reasonable time.
 */
const store = createPreviewStore();
const STALE_MS = 30_000;

/**
 * The tooltip only shows a short snippet, so fetch just the head of the file instead of the
 * whole body (which can be up to 512 KB). lineCount/language in the response still describe
 * the entire file, and GitService clamps the range safely (empty file → empty content).
 */
const PREVIEW_LINES = 20;

function fetchPreview(path) {
  return gitApi.getFileContent(path, 1, PREVIEW_LINES);
}

/** Drops a cached preview so the next hover re-fetches it. */
export function invalidateFilePreviewCache(path) {
  if (path == null) return;
  store.invalidate(path);
}

/** Drops every cached file preview — e.g. after a known external repo refresh. */
export function invalidateAllFilePreviewCache() {
  store.invalidateAll();
}

/**
 * Fetches (or returns cached) a file preview: metadata + content for the tooltip/modal.
 * Mirrors useDocPreview's strategy (see usePreviewCache: module cache → in-flight subscribe →
 * fresh fetch), but keyed by repo-relative path and with TTL-based expiry instead of manual
 * invalidation only.
 *
 * @param {string|null} path    – repo-relative file path to preview (null = disabled)
 * @param {boolean}     enabled – only fetch when true (hover active / modal open)
 */
export default function useFilePreview(path, enabled) {
  const { value, loading, error } = usePreviewCache(store, path, enabled, fetchPreview, {
    ttlMs: STALE_MS,
  });

  return { file: value, loading, error };
}
