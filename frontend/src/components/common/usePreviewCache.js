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
 * @param {(key: *) => *} [options.instantLookup] – synchronous pre-check that
 *   bypasses cache/fetch when it returns a non-nullish value
 */
export default function usePreviewCache(store, key, enabled, fetcher, options = {}) {
  const { ttlMs, instantLookup } = options;
  const [value, setValue] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const prevKeyRef = useRef(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const instantLookupRef = useRef(instantLookup);
  instantLookupRef.current = instantLookup;

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

    // 1. Instant path (e.g. lookup in an already-loaded tree)
    const instant = instantLookupRef.current?.(key);
    if (instant != null) {
      setValue(instant);
      setLoading(false);
      return undefined;
    }

    const { cache, listeners, notify } = store;

    // 2. Module cache hit
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

    // 3. Already in-flight — subscribe to result
    if (cached?.value === 'loading') {
      setLoading(true);
      const cb = (val) => {
        if (val === 'error') {
          setError(true);
          setLoading(false);
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
    setLoading(true);

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
        setError(true);
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fetcher/instantLookup read via ref, namely not triggers
  }, [key, enabled, ttlMs, store]);

  return { value, loading, error };
}
