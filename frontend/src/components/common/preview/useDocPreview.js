import { useCallback, useMemo } from 'react';
import api from '../../../api/documentsApi';
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
 * Strategy (see usePreviewCache): module cache → tree stub as a seed →
 * in-flight subscribe → cancellation-aware fetch via api.fetchById.
 *
 * The tree node is a SEED, not an answer: the backend truncates `description` to
 * 150 characters in tree/children listings (DocumentService.SNIPPET_LENGTH), so
 * it is enough to fill the tooltip instantly but never enough for the expanded
 * preview — hence `_stub`, and hence the fetch that runs anyway. Short-circuiting
 * on it (as this hook used to) is what cut expanded previews off mid-sentence
 * once a search had pulled the linked documents into the tree.
 *
 * @param {string|null} id       – document id to preview (null = disabled)
 * @param {Array}       tree     – KB tree used to seed the preview (chat has none)
 * @param {boolean}     enabled  – only fetch when true (hover active)
 */
export default function useDocPreview(id, tree, enabled) {
  // Пересобирается только со сменой дерева: usePreviewCache держит seed в
  // зависимостях, а обход дерева на каждый рендер тултипа не нужен.
  const seed = useCallback(
    (key) => {
      const fromTree = findInTree(tree, key);
      return fromTree && fromTree.description !== undefined ? { ...fromTree, _stub: true } : undefined;
    },
    [tree],
  );
  const options = useMemo(() => ({ seed }), [seed]);

  const { value, loading, error } = usePreviewCache(store, id, enabled, api.fetchById, options);

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
