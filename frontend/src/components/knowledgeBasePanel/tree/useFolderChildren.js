import { useState, useEffect } from 'react';
import { KB_FULL_PAGE as FULL_PAGE } from '@/constants/pagination';

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
 * Children DATA is owned by the shared tree, not by this hook: the list is
 * fetched THROUGH `loadChildren` (the KB's deduplicated handleLoadChildren),
 * which splices the result into the tree. The panel re-renders because
 * `node.children` (synced onto the selected node) updates — so `children` is
 * simply DERIVED from it rather than kept as a second copy in state.
 *
 * `loadChildren` обязателен: без него хук знал бы о детях больше дерева, а
 * читают их и панель состава, и сам узел дерева.
 *
 * Returns { children, loading } where `loading` is true only until the first
 * server response for the current folder arrives.
 */
export default function useFolderChildren(node, loadChildren) {
  const nodeId = node?.id ?? null;
  // Запрос нужен, только пока дерево не держит полный список этой папки.
  const needsFetch = !!nodeId && node.type === 'folder' && !treeCacheIsComplete(node);
  // Папка, ответ по которой уже получен. Отсюда же `loading`: отдельным
  // состоянием он был бы setState в теле эффекта, то есть лишним рендером на
  // каждое открытие папки.
  const [answeredId, setAnsweredId] = useState(null);
  const loading = needsFetch && answeredId !== nodeId;

  useEffect(() => {
    if (!needsFetch) return undefined;
    let cancelled = false;

    Promise.resolve(loadChildren(nodeId, 0, FULL_PAGE))
      .catch(() => {
        // Network/server error: keep whatever the tree already holds.
      })
      .finally(() => {
        if (!cancelled) setAnsweredId(nodeId);
      });

    return () => {
      cancelled = true;
    };
    // needsFetch — зависимость наравне с nodeId: выбранная папка может стать
    // неполной без смены узла (refreshScope заменяет детей нулевой страницей), и
    // тогда список нужно перечитать. Без него `loading` включался бы навсегда:
    // он выводится из живого needsFetch, а запроса бы не было.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeId, needsFetch]);

  return { children: node?.children ?? [], loading };
}
