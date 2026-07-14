import { useState, useEffect } from 'react';
import api from '../../api/documentsApi';
import { KB_FULL_PAGE as FULL_PAGE } from '../../constants/pagination';

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
 * Loads the full child list for a folder node and reports loading state.
 *
 * Children DATA is owned by the shared tree, not by this hook: when a loader is
 * provided (`loadChildren`, wired to the KB's deduplicated handleLoadChildren),
 * the full list is fetched THROUGH that loader, which splices the result into
 * the tree. The detail panel re-renders because `node.children` (synced onto the
 * selected node) updates — so we simply DERIVE `children` from `node.children`
 * rather than keeping a second copy in state and syncing it with effects.
 *
 * A tiny local `direct` state is kept ONLY for the loaderless fallback (the hook
 * used outside the KB tree), where nothing splices into a shared tree.
 *
 * Returns { children, loading } where `loading` is true only until the first
 * server response for the current folder arrives.
 */
export default function useFolderChildren(node, loadChildren) {
  const [loading, setLoading] = useState(false);
  const [direct, setDirect] = useState(null); // loaderless fallback only

  useEffect(() => {
    setDirect(null);

    if (!node?.id || node.type !== 'folder') {
      setLoading(false);
      return undefined;
    }

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
    Promise.resolve(loadChildren ? loadChildren(node.id, 0, FULL_PAGE) : api.fetchChildren(node.id, 0, FULL_PAGE))
      .then((paged) => {
        // With a shared loader the tree updates itself; only the fallback needs
        // to stash the items locally.
        if (cancelled || loadChildren) return;
        setDirect(Array.isArray(paged?.items) ? paged.items : []);
      })
      .catch(() => {
        // Network/server error: keep whatever the tree/fallback already holds.
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [node?.id]);

  const children = loadChildren ? node?.children ?? [] : direct ?? node?.children ?? [];

  return { children, loading };
}
