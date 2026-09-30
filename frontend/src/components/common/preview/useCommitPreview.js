import gitApi from '@/api/gitApi';
import usePreviewCache, { createPreviewStore } from './usePreviewCache';

/**
 * Module-level store: project + revision → GitCommit (with `files`) | 'error'.
 * Keyed by the pair for the same reason as useFilePreview: a short hash or a
 * branch name means a different commit in every repository.
 *
 * A full hash names an immutable commit, but a link may name a branch, and a
 * hash the repository has not fetched yet turns into a commit after the next
 * fetch — so entries expire like file previews do instead of living forever.
 */
const store = createPreviewStore();
const STALE_MS = 30_000;

const previewKey = (project, rev) => (rev ? `${project || ''}\u0000${rev}` : null);

function fetchPreview(key) {
  const sep = key.indexOf('\u0000');
  return gitApi.getCommit(key.slice(sep + 1), { project: key.slice(0, sep) });
}

/**
 * Fetches (or returns cached) a commit for a commit-link tooltip: message, author,
 * date and the changed files (no patches) — one `GET /api/git/commit`, the same
 * request the Files panel's Commit tab makes.
 *
 * @param {string|null} rev     – commit hash (or any revision) to preview (null = disabled)
 * @param {string|null} project – project the commit belongs to (null = the default one)
 * @param {boolean}     enabled – only fetch when true (hover active)
 */
export default function useCommitPreview(rev, project, enabled) {
  const { value, loading, error } = usePreviewCache(store, previewKey(project, rev), enabled, fetchPreview, {
    ttlMs: STALE_MS,
  });

  return { commit: value, loading, error };
}
