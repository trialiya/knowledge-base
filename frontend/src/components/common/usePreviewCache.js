import { useState, useEffect, useRef } from 'react';

/**
 * Creates an isolated module-level cache store: key → { value, fetchedAt }.
 * Each preview feature (doc links, file links, …) owns one store so their
 * caches and in-flight listeners never collide, even though they share this
 * hook's fetch/subscribe/cancellation machinery.
 */
export function createPreviewStore() {
  const cache = new Map();
  const listeners = new Map();

  function notify(key, value) {
    cache.set(key, { value, fetchedAt: Date.now() });
    listeners.get(key)?.forEach((cb) => cb(value));
    listeners.delete(key);
  }

  /** Drops a cached entry so the next lookup re-fetches it. */
  function invalidate(key) {
    if (key == null) return;
    cache.delete(key);
  }

  /** Drops every cached entry in this store. */
  function invalidateAll() {
    cache.clear();
  }

  return { cache, listeners, notify, invalidate, invalidateAll };
}

/**
 * A 'loading' entry is never "fresh" — it must always fall through to the
 * in-flight-subscribe branch below, regardless of ttlMs. Otherwise a second
 * hook instance landing inside the TTL window while the first fetch is still
 * in flight would read the literal string 'loading' as the resolved value.
 */
function isFresh(entry, ttlMs) {
  if (!entry || entry.value === 'loading') return false;
  if (ttlMs == null) return true; // eternal cache: resolved entries never expire
  return Date.now() - entry.fetchedAt < ttlMs;
}

/**
 * Fetches (or returns cached) a preview value: module cache → in-flight
 * subscribe → cancellation-aware fetch. Shared strategy behind useDocPreview
 * and useFilePreview.
 *
 * @param {object}   store    – createPreviewStore() instance
 * @param {*}        key      – cache key (falsy = disabled, matches useDocPreview/useFilePreview's original id/path guards)
 * @param {boolean}  enabled  – only fetch when true
 * @param {(key: *) => Promise<*>} fetcher – resolves the value for key
 * @param {object}   [options]
 * @param {number}   [options.ttlMs] – cache entry TTL; omitted = never expires
 * @param {(key: *) => *} [options.seed] – synchronous lookup rendered immediately
 *   (e.g. a stub from an already-loaded tree) so there is no loading flash. It is
 *   a HEAD START, not a substitute: the fetch still runs and replaces it. It used
 *   to short-circuit the fetch entirely, which is how a doc preview could end up
 *   showing the tree's 150-char snippet as if it were the whole document.
 */
export default function usePreviewCache(store, key, enabled, fetcher, options = {}) {
  const { ttlMs, seed } = options;
  const [value, setValue] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const prevKeyRef = useRef(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const seedRef = useRef(seed);
  seedRef.current = seed;

  useEffect(() => {
    if (!key || !enabled) {
      setValue(null);
      setLoading(false);
      setError(false);
      return undefined;
    }

    // Reset on key change
    if (prevKeyRef.current !== key) {
      prevKeyRef.current = key;
      setValue(null);
      setError(false);
    }

    const { cache, listeners, notify } = store;

    // 1. Module cache hit — an already resolved (complete) value beats any seed
    const cached = cache.get(key);
    if (isFresh(cached, ttlMs)) {
      if (cached.value === 'error') {
        setError(true);
        setLoading(false);
      } else {
        setValue(cached.value);
        setLoading(false);
      }
      return undefined;
    }

    // 2. Seed: render what we already have (a tree stub) instead of a spinner,
    //    then fall through — the fetch below replaces it with the full value.
    const seeded = seedRef.current?.(key);
    if (seeded != null) setValue(seeded);

    // Затравка есть — ошибку догрузки не показываем: лучше неполный, но живой
    // предпросмотр, чем «не найдено» вместо уже показанного узла.
    const failed = () => {
      if (seeded == null) setError(true);
      setLoading(false);
    };

    // 3. Already in-flight — subscribe to result
    if (cached?.value === 'loading') {
      setLoading(seeded == null);
      const cb = (val) => {
        if (val === 'error') {
          failed();
        } else {
          setValue(val);
          setLoading(false);
        }
      };
      if (!listeners.has(key)) listeners.set(key, new Set());
      listeners.get(key).add(cb);
      return () => listeners.get(key)?.delete(cb);
    }

    // 4. Fresh fetch (cancellation-aware)
    let cancelled = false;
    cache.set(key, { value: 'loading', fetchedAt: Date.now() });
    setLoading(seeded == null);

    fetcherRef
      .current(key)
      .then((result) => {
        notify(key, result); // populate cache + wake other waiters regardless
        if (cancelled) return;
        setValue(result);
        setLoading(false);
      })
      .catch(() => {
        notify(key, 'error');
        if (cancelled) return;
        failed();
      });

    return () => {
      cancelled = true;
    };
  }, [key, enabled, ttlMs, store]);

  return { value, loading, error };
}
