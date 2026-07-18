import { useRef } from 'react';
import api from '../../api/documentsApi';
import usePreviewCache, { createPreviewStore } from './usePreviewCache';

/**
 * Module-level store: id (string) → DocumentNode | 'loading' | 'error'.
 * Lives for the page lifetime, so repeated hovers on the same link are instant.
 */
const store = createPreviewStore();

/**
 * Drops a cached preview so the next hover re-fetches it. Called by
 * useKnowledgeBase after a successful edit/summarize, so a doc-link tooltip
 * hovered again after the change shows the fresh description/summary instead
 * of whatever was cached from before the edit.
 */
export function invalidateDocPreviewCache(id) {
  if (id == null) return;
  store.invalidate(Number(id));
}

/**
 * Fetches (or returns cached) a document preview node.
 *
 * Strategy (see usePreviewCache): instant tree lookup → module cache →
 * in-flight subscribe → cancellation-aware fetch via api.fetchById.
 *
 * @param {string|null} id       – document id to preview (null = disabled)
 * @param {Array}       tree     – KB tree for instant-lookup before fetch (chat has none)
 * @param {boolean}     enabled  – only fetch when true (hover active)
 */
export default function useDocPreview(id, tree, enabled) {
  const treeRef = useRef(tree);
  treeRef.current = tree; // всегда последний tree, но НЕ триггер эффекта

  const { value, loading, error } = usePreviewCache(store, id, enabled, api.fetchById, {
    instantLookup: () => {
      const fromTree = findInTree(treeRef.current, id);
      return fromTree && fromTree.description !== undefined ? fromTree : undefined;
    },
  });

  return { node: value, loading, error };
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
