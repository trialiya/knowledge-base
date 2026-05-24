import { useState, useEffect } from 'react';
import api from './api';

const FULL_PAGE = 1000;

/**
 * Decides whether the tree's cached children for this folder are already the
 * complete set, so the detail panel can render without re-fetching.
 *
 * The tree records the server-reported total on the node as `_totalChildren`.
 * If we've loaded as many as the total (and the tree marked the node loaded),
 * the cache is authoritative.
 */
function treeCacheIsComplete(node) {
  if (!node?._childrenLoaded) return false;
  const total = node._totalChildren;
  const loaded = node.children?.length ?? 0;
  if (total == null) return false; // unknown total → treat as incomplete
  return loaded >= total;
}

/**
 * Loads and owns the full child list for a folder node.
 *
 * Single source of truth for the children DATA, but NOT a second network path:
 * when a loader is provided (`loadChildren`, wired to the KB's deduplicated
 * handleLoadChildren), the full list is fetched THROUGH that loader so the
 * result is spliced into the shared tree and any concurrent tree-expand for the
 * same folder collapses into one request. The local `children` state then just
 * mirrors `node.children`.
 *
 * Falls back to a direct api.fetchChildren only when no loader is supplied.
 *
 * Returns { children, loading } where `loading` is true only until the first
 * server response for the current folder arrives.
 */
export default function useFolderChildren(node, loadChildren) {
  const seed = node?.children ?? [];
  const [children, setChildren] = useState(seed);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!node?.id || node.type !== 'folder') {
      setChildren([]);
      setLoading(false);
      return undefined;
    }

    // Instant paint from the tree's cached children, if any.
    setChildren(node.children ?? []);

    // Fast path: tree already holds the complete list — no request needed.
    if (treeCacheIsComplete(node)) {
      setLoading(false);
      return undefined;
    }

    let cancelled = false;
    setLoading(true);

    // Prefer the shared, deduplicated loader so the request is shared with the
    // tree (and lands in the tree cache). Fall back to a direct fetch only if
    // no loader was provided.
    const request = loadChildren ? loadChildren(node.id, 0, FULL_PAGE) : api.fetchChildren(node.id, 0, FULL_PAGE);

    Promise.resolve(request)
      .then((paged) => {
        if (cancelled) return;
        setChildren(Array.isArray(paged?.items) ? paged.items : []);
      })
      .catch(() => {
        // Network/server error: keep the seeded children rather than blanking.
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node?.id]);

  // Keep in sync when the tree pushes a child set (e.g. after the shared loader
  // splices results, or after create).
  useEffect(() => {
    const fromTree = node?.children;
    if (fromTree && fromTree.length !== children.length) {
      setChildren(fromTree);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node?.children?.length]);

  return { children, loading };
}
