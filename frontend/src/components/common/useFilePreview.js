import gitApi from '../../api/gitApi';
import usePreviewCache, { createPreviewStore } from './usePreviewCache';

/**
 * Module-level store: project + path → GitFileContent | 'error'. The key is a
 * pair because the path alone is not an address: `backend/build.gradle` exists in
 * every repository, and one cache for all of them would answer a hover in one
 * project with another project's file — and the answer would look right. Unlike
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

/**
 * Cache key. `usePreviewCache` compares keys with `!==` and uses them as Map
 * keys, so the pair has to collapse into one primitive; the separator is a
 * character no project id or path may contain.
 */
const previewKey = (project, path) => (path == null ? null : `${project || ''}\u0000${path}`);

function fetchPreview(key) {
  const sep = key.indexOf('\u0000');
  const project = key.slice(0, sep);
  const path = key.slice(sep + 1);
  return gitApi.getFileContent(path, { from: 1, to: PREVIEW_LINES, project });
}

/** Drops a cached preview so the next hover re-fetches it. */
export function invalidateFilePreviewCache(project, path) {
  if (path == null) return;
  store.invalidate(previewKey(project, path));
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
 * @param {string|null} project – project the path belongs to (null = the default one)
 * @param {boolean}     enabled – only fetch when true (hover active / modal open)
 */
export default function useFilePreview(path, project, enabled) {
  const { value, loading, error } = usePreviewCache(store, previewKey(project, path), enabled, fetchPreview, {
    ttlMs: STALE_MS,
  });

  return { file: value, loading, error };
}
