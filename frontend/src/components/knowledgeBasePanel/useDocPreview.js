import { useState, useEffect, useRef } from 'react';
import api from './api';

/**
 * Module-level cache: id (string) → DocumentNode | 'loading' | 'error'
 * Lives for the page lifetime, so repeated hovers on the same link are instant.
 */
const cache = new Map();

/** Listeners waiting for a specific id to resolve: id → Set<(node) => void> */
const listeners = new Map();

function notify(id, value) {
  cache.set(id, value);
  listeners.get(id)?.forEach((cb) => cb(value));
  listeners.delete(id);
}

/**
 * Fetches (or returns cached) a document preview node.
 *
 * Strategy:
 *   1. If the tree already has the node with a full description → use it instantly.
 *   2. If already in module cache → return immediately.
 *   3. If already in-flight → subscribe to the result.
 *   4. Otherwise fetch via api.fetchById, store in cache, notify any waiters.
 *
 * The fresh-fetch path is cancellation-aware: if `id`/`enabled` change while a
 * request is in flight, the cleanup flips `cancelled` so the late resolution
 * still populates the shared cache (via notify) but never calls this instance's
 * setters for a now-stale id.
 *
 * @param {string|null} id       – document id to preview (null = disabled)
 * @param {Array}       tree     – KB tree for instant-lookup before fetch
 * @param {boolean}     enabled  – only fetch when true (hover active)
 */
export default function useDocPreview(id, tree, enabled) {
  const [node, setNode] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const prevIdRef = useRef(null);
  const treeRef = useRef(tree);
  treeRef.current = tree; // всегда последний tree, но НЕ триггер эффекта

  useEffect(() => {
    if (!id || !enabled) {
      setNode(null);
      setLoading(false);
      setError(false);
      return undefined;
    }

    // Reset on id change
    if (prevIdRef.current !== id) {
      prevIdRef.current = id;
      setNode(null);
      setError(false);
    }

    // 1. Instant path: find in tree (only if node has description loaded)
    const fromTree = findInTree(treeRef.current, id);
    if (fromTree && fromTree.description !== undefined) {
      setNode(fromTree);
      setLoading(false);
      return undefined;
    }

    // 2. Module cache hit
    const cached = cache.get(id);
    if (cached && cached !== 'loading') {
      if (cached === 'error') {
        setError(true);
        setLoading(false);
      } else {
        setNode(cached);
        setLoading(false);
      }
      return undefined;
    }

    // 3. Already in-flight — subscribe to result
    if (cached === 'loading') {
      setLoading(true);
      const cb = (val) => {
        if (val === 'error') {
          setError(true);
          setLoading(false);
        } else {
          setNode(val);
          setLoading(false);
        }
      };
      if (!listeners.has(id)) listeners.set(id, new Set());
      listeners.get(id).add(cb);
      return () => listeners.get(id)?.delete(cb);
    }

    // 4. Fresh fetch (cancellation-aware)
    let cancelled = false;
    cache.set(id, 'loading');
    setLoading(true);

    api
      .fetchById(id)
      .then((result) => {
        notify(id, result); // populate cache + wake other waiters regardless
        if (cancelled) return;
        setNode(result);
        setLoading(false);
      })
      .catch(() => {
        notify(id, 'error');
        if (cancelled) return;
        setError(true);
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [id, enabled]); // tree читается через treeRef — намеренно не триггерит эффект

  return { node, loading, error };
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function findInTree(tree, id) {
  if (!Array.isArray(tree)) return null;
  for (const node of tree) {
    if (String(node.id) === String(id)) return node;
    if (node.children) {
      const found = findInTree(node.children, id);
      if (found) return found;
    }
  }
  return null;
}
