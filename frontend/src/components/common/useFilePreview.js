import { useState, useEffect, useRef } from 'react';
import gitApi from '../../api/gitApi';

/**
 * Module-level cache: path (string) → { value: GitFileContent | 'error', fetchedAt: number }
 * Unlike useDocPreview, entries expire after STALE_MS — the repo can change from outside the
 * app (e.g. `git pull` run in a terminal), so a long-lived cache would show stale content
 * indefinitely. A short TTL keeps repeated hovers instant while still picking up external changes
 * within a reasonable time.
 */
const cache = new Map();
const listeners = new Map();
const STALE_MS = 30_000;

function notify(path, value) {
  cache.set(path, { value, fetchedAt: Date.now() });
  listeners.get(path)?.forEach((cb) => cb(value));
  listeners.delete(path);
}

/** Drops a cached preview so the next hover re-fetches it. */
export function invalidateFilePreviewCache(path) {
  if (path == null) return;
  cache.delete(path);
}

/** Drops every cached file preview — e.g. after a known external repo refresh. */
export function invalidateAllFilePreviewCache() {
  cache.clear();
}

function isFresh(entry) {
  return entry && Date.now() - entry.fetchedAt < STALE_MS;
}

/**
 * Fetches (or returns cached) a file preview: metadata + content for the tooltip/modal.
 * Mirrors useDocPreview's strategy (module cache → in-flight subscribe → fresh fetch), but keyed
 * by repo-relative path and with TTL-based expiry instead of manual invalidation only.
 *
 * @param {string|null} path    – repo-relative file path to preview (null = disabled)
 * @param {boolean}     enabled – only fetch when true (hover active / modal open)
 */
export default function useFilePreview(path, enabled) {
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const prevPathRef = useRef(null);

  useEffect(() => {
    if (!path || !enabled) {
      setFile(null);
      setLoading(false);
      setError(false);
      return undefined;
    }

    if (prevPathRef.current !== path) {
      prevPathRef.current = path;
      setFile(null);
      setError(false);
    }

    const cached = cache.get(path);
    if (isFresh(cached)) {
      if (cached.value === 'error') {
        setError(true);
        setLoading(false);
      } else {
        setFile(cached.value);
        setLoading(false);
      }
      return undefined;
    }

    if (cached?.value === 'loading') {
      setLoading(true);
      const cb = (val) => {
        if (val === 'error') {
          setError(true);
          setLoading(false);
        } else {
          setFile(val);
          setLoading(false);
        }
      };
      if (!listeners.has(path)) listeners.set(path, new Set());
      listeners.get(path).add(cb);
      return () => listeners.get(path)?.delete(cb);
    }

    let cancelled = false;
    cache.set(path, { value: 'loading', fetchedAt: Date.now() });
    setLoading(true);

    gitApi
      .getFileContent(path)
      .then((result) => {
        notify(path, result);
        if (cancelled) return;
        setFile(result);
        setLoading(false);
      })
      .catch(() => {
        notify(path, 'error');
        if (cancelled) return;
        setError(true);
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [path, enabled]);

  return { file, loading, error };
}
