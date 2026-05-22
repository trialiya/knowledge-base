import { useState, useEffect } from 'react';
import api from './api';

const FULL_PAGE = 1000;

/**
 * Loads and owns the full child list for a folder node.
 *
 * Single source of truth: it seeds from whatever the tree already has
 * (node.children) for an instant first paint, then fetches the authoritative
 * full list from the server. State is keyed to node.id so switching folders
 * resets cleanly and an in-flight request for a previous folder can't clobber
 * the new one.
 *
 * Returns { children, loading } where `loading` is true only until the first
 * server response for the current folder arrives.
 */
export default function useFolderChildren(node) {
  const seed = node?.children ?? [];
  const [children, setChildren] = useState(seed);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!node?.id || node.type !== 'folder') {
      setChildren([]);
      setLoading(false);
      return undefined;
    }

    let cancelled = false;

    // Instant paint from the tree's cached children, if any.
    setChildren(node.children ?? []);
    setLoading(true);

    api
      .fetchChildren(node.id, 0, FULL_PAGE)
      .then((paged) => {
        if (cancelled) return;
        // Authoritative replace — including the empty case, so a truly empty
        // folder correctly shows "пусто" instead of stale siblings.
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
  }, [node?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Keep in sync when the tree pushes a larger child set (e.g. after create).
  useEffect(() => {
    const fromTree = node?.children;
    if (fromTree && fromTree.length > children.length) {
      setChildren(fromTree);
    }
  }, [node?.children?.length]); // eslint-disable-line react-hooks/exhaustive-deps

  return { children, loading };
}
