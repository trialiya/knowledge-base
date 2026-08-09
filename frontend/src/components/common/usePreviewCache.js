import { useState, useEffect, useEffectEvent, useMemo } from 'react';

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
 * What is known about `key` synchronously, before any fetch: a fresh module
 * cache entry, otherwise the seed. Pure — so the first render already shows the
 * cached value instead of a spinner that an effect would replace one frame later.
 */
function knownNow(store, key, enabled, ttlMs, seed) {
  if (!key || !enabled) return { value: null, loading: false, error: false };

  // 1. Module cache hit — an already resolved (complete) value beats any seed
  const cached = store.cache.get(key);
  if (isFresh(cached, ttlMs)) {
    if (cached.value === 'error') return { value: null, loading: false, error: true };
    return { value: cached.value, loading: false, error: false };
  }

  // 2. Seed: render what we already have (a tree stub) instead of a spinner,
  //    then let the effect's fetch replace it with the full value.
  const seeded = seed?.(key) ?? null;
  return { value: seeded, loading: seeded == null, error: false };
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
 *   a HEAD START, not a substitute: the fetch still runs and replaces it, so a doc
 *   preview never keeps the tree's 150-char snippet as if it were the whole
 *   document. Must be memoized by the caller — it is read during render and takes
 *   part in the deps below, so a new identity on every render re-runs the effect.
 */
export default function usePreviewCache(store, key, enabled, fetcher, options = {}) {
  const { ttlMs, seed } = options;
  // Только то, что пришло асинхронно (fetch или чужой in-flight). Всё, что
  // известно синхронно, считается в knownNow при рендере: setState в теле
  // эффекта дал бы каскадный ре-рендер на каждое наведение мыши.
  const [resolved, setResolved] = useState(null); // { value, error } | null
  // Запрос уходит только из эффекта, поэтому fetcher — эффект-событие: вызов
  // всегда идёт в свежую функцию, а её смена не перезапускает подписку.
  const runFetch = useEffectEvent((k) => fetcher(k));

  // Смена ключа обнуляет ответ предыдущего — иначе кадр до перезапроса показал
  // бы содержимое чужого документа.
  const [prev, setPrev] = useState({ key, enabled, ttlMs, store });
  if (prev.key !== key || prev.enabled !== enabled || prev.ttlMs !== ttlMs || prev.store !== store) {
    setPrev({ key, enabled, ttlMs, store });
    setResolved(null);
  }

  // Те же зависимости, что у эффекта: seed (обход дерева) не должен гоняться на
  // каждый ре-рендер модалки — за это отвечает мемоизация seed у вызывающего.
  const known = useMemo(() => knownNow(store, key, enabled, ttlMs, seed), [store, key, enabled, ttlMs, seed]);

  useEffect(() => {
    if (!key || !enabled) return undefined;

    const { cache, listeners, notify } = store;
    const cached = cache.get(key);
    if (isFresh(cached, ttlMs)) {
      // Обычно это то же, что уже отрисовано из кэша, и тогда условие ниже
      // ложно. Но кэш мог наполниться между рендером и этим (пассивным)
      // эффектом — запросом соседнего экземпляра, который успел дойти. Тогда
      // подписываться не на что (notify снимает слушателей) и запрашивать
      // нечего, а на экране всё ещё спиннер или затравка. Лишний проход рендера
      // здесь — цена гонки, которая иначе оставила бы спиннер навсегда.
      const fresh = cached.value === 'error' ? { value: null, error: true } : { value: cached.value, error: false };
      // eslint-disable-next-line react-hooks/set-state-in-effect
      if (fresh.value !== known.value || fresh.error !== known.error) setResolved(fresh);
      return undefined;
    }

    const seeded = seed?.(key) ?? null;

    // Затравка есть — ошибку догрузки не показываем: лучше неполный, но живой
    // предпросмотр, чем «не найдено» вместо уже показанного узла.
    const failed = () => setResolved({ value: seeded, error: seeded == null });

    // Already in-flight — subscribe to result
    if (cached?.value === 'loading') {
      const cb = (val) => {
        if (val === 'error') failed();
        else setResolved({ value: val, error: false });
      };
      if (!listeners.has(key)) listeners.set(key, new Set());
      listeners.get(key).add(cb);
      return () => listeners.get(key)?.delete(cb);
    }

    // Fresh fetch (cancellation-aware)
    let cancelled = false;
    cache.set(key, { value: 'loading', fetchedAt: Date.now() });

    runFetch(key)
      .then((result) => {
        notify(key, result); // populate cache + wake other waiters regardless
        if (cancelled) return;
        setResolved({ value: result, error: false });
      })
      .catch(() => {
        notify(key, 'error');
        if (cancelled) return;
        failed();
      });

    return () => {
      cancelled = true;
    };
    // known — та же мемоизация, что и у зависимостей выше, поэтому лишних
    // перезапусков не даёт; если React всё же пересчитает мемо, эффект попадёт
    // в ветку подписки на уже идущий запрос и второго обращения не будет.
  }, [key, enabled, ttlMs, store, known, seed]);

  if (resolved) return { value: resolved.value, loading: false, error: resolved.error };
  return known;
}
