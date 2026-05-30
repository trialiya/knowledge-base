import { useState, useEffect, useCallback, useRef } from 'react';

import api from './api';
import { getSiblings, applyReorder, parentTitle, isParentChange, updateNodeInTree, spliceChildren } from './treeOps';
import { findNodeById, findPath } from '../Utils/utils';

const PAGE_SIZE = 10;
// Used when we must guarantee the WHOLE child list is present (e.g. restoring
// the path to a selected node after refresh / direct-link navigation), so a
// node sitting on page 2+ of its parent isn't missing from the tree.
const FULL_PAGE = 1000;

/**
 * Encapsulates every piece of Knowledge Base state, side effect and handler.
 * The component consuming this hook is purely presentational.
 */
/**
 * props (из useAppNavigation через KnowledgeBase.jsx):
 *   docId      — какой документ показать (null = ничего/поиск)
 *   docTab     — вкладка детали (summary/content/…)
 *   search     — поисковый запрос
 *   mode       — режим поиска
 *   onOpenDoc(id, tab?)      — KB сообщает навигации: выбран документ
 *   onSearch(query, mode)    — KB сообщает навигации: запущен поиск
 *   onTabChange(tab)         — KB сообщает навигации: сменилась вкладка детали
 *
 * Хук НЕ пишет URL и НЕ слушает popstate — этим владеет useAppNavigation.
 */
export default function useKnowledgeBase({
  docId: navDocId = null,
  docTab: navDocTab = 'summary',
  search: navSearch = '',
  mode: navMode = 'hybrid',
  onOpenDoc,
  onSearch,
  onTabChange,
} = {}) {
  const [tree, setTree] = useState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [activeTab, setActiveTab] = useState(navDocTab || 'summary');
  const [searchQuery, setSearchQuery] = useState(navSearch || '');
  const [searchMode, setSearchMode] = useState(navMode || 'hybrid');
  const [searchResults, setSearchResults] = useState([]);
  const [showAddModal, setShowAddModal] = useState(false);
  const [notFoundDocId, setNotFoundDocId] = useState(null);
  const [docLoadError, setDocLoadError] = useState(null); // { status, docId }
  const [saveError, setSaveError] = useState(null); // { message, id }
  const [refreshing, setRefreshing] = useState(false);

  // Pending drop info awaiting user confirmation: { dropInfo, fromTitle, toTitle }
  const [moveConfirm, setMoveConfirm] = useState(null);

  // Guards the one-time init effect against React 18 StrictMode's intentional
  // double-invocation of effects in development, which would otherwise fire the
  // whole tree + ancestor-chain fetch sequence twice on first load.
  const didInitRef = useRef(false);

  // In-flight children requests, keyed by `${parentId}|${page}|${size}`. Lets
  // concurrent callers (tree expand + folder detail) share one network request
  // instead of each firing their own.
  const inflightChildrenRef = useRef(new Map());

  // ── Tree loading ─────────────────────────────────────────────────────────

  const loadTree = useCallback(async () => {
    try {
      const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
      setTree(Array.isArray(paged.items) ? paged.items : []);
      return paged;
    } catch {
      setTree([]);
      return null;
    }
  }, []);

  /**
   * Lazy-loads one page of children for a node and splices them into the tree.
   * Called by TreeNode when a folder is expanded or "load more" is clicked,
   * and by the folder detail (via the full-list loader below).
   *
   * Concurrent calls for the same parentId+page+size share a single in-flight
   * request, so a row click that both selects and expands a folder can't fire
   * two identical fetches.
   */
  const handleLoadChildren = useCallback(async (parentId, page = 0, size = PAGE_SIZE) => {
    const key = `${parentId}|${page}|${size}`;
    const inflight = inflightChildrenRef.current;
    if (inflight.has(key)) return inflight.get(key);

    const promise = (async () => {
      try {
        const paged = await api.fetchChildren(parentId, page, size);
        setTree((prev) => {
          const clone = JSON.parse(JSON.stringify(prev));
          spliceChildren(clone, parentId, paged, { replace: page === 0 });
          return clone;
        });
        return paged;
      } catch {
        return null;
      } finally {
        inflight.delete(key);
      }
    })();

    inflight.set(key, promise);
    return promise;
  }, []);

  /** Reloads a parent's children scope after a mutation (create/delete). */
  const refreshScope = useCallback(
    async (parentId) => {
      if (parentId === null) {
        await loadTree();
        return;
      }
      const paged = await api.fetchChildren(parentId, 0, PAGE_SIZE);
      setTree((prev) => {
        const clone = JSON.parse(JSON.stringify(prev));
        spliceChildren(clone, parentId, paged, { replace: true });
        return clone;
      });
    },
    [loadTree],
  );

  // ── Search ─────────────────────────────────────────────────────────────────

  const performSearch = useCallback(async (query, mode) => {
    if (!query.trim()) {
      setSearchResults([]);
      return;
    }
    try {
      const data = await api.search(query, mode);
      setSearchResults(Array.isArray(data) ? data : []);
    } catch {
      setSearchResults([]);
    }
  }, []);

  // ── Navigation & URL sync ────────────────────────────────────────────────

  /**
   * Core selection logic — expects a full DocumentNode with `type` populated.
   * Separated from selectNode so it can be called after async fetch enrichment.
   */
  const applySelectNode = useCallback(
    (node, { notify = true } = {}) => {
      // `_full` marks this as a complete document (from GET /documents/{id}),
      // so the tree-sync effect won't later overwrite its description with a
      // tree stub (which carries only a ≤150-char snippet).
      setSelectedNode({ ...node, _full: true });
      setActiveTab('summary');
      setSearchResults([]);
      setSearchQuery('');
      setNotFoundDocId(null);
      setDocLoadError(null);
      // Сообщаем навигации (она пишет URL). notify=false, когда выбор пришёл
      // ИЗ навигации (prop docId) — иначе была бы петля.
      if (notify && onOpenDoc) onOpenDoc(node.id, 'summary');

      // NOTE: folder children are loaded by FolderDetail's useFolderChildren
      // through the shared (deduplicated) loader. We intentionally do NOT fetch
      // them here — doing so fired a second, differently-sized request
      // (size=10 here vs size=1000 there) that couldn't be deduplicated.
    },
    [onOpenDoc],
  );

  /**
   * Select a node for display in the detail panel.
   *
   * Tree nodes from /api/documents/children carry only a short snippet of description
   * (≤150 chars) to keep list payloads lean. Search result DTOs also lack full content.
   * In all cases we fetch the complete document via GET /api/documents/{id} before
   * rendering, then patch it back into the tree cache so the tree-sync effect never
   * overwrites the full content with a stale stub.
   */
  /**
   * Fetches the COMPLETE document via GET /api/documents/{id} and selects it.
   * Used by every selection path (tree click, cross-component navigation, direct
   * link, refresh) because tree/search nodes carry only a ≤150-char snippet of
   * the description — never the full content the Summary/Content tabs need.
   *
   * The full data is also patched back into the tree cache so the tree-sync
   * effect can't later overwrite the detail with a stale stub.
   */
  const fetchFullAndSelect = useCallback(
    (idOrNode, opts = {}) => {
      const id = typeof idOrNode === 'object' ? idOrNode.id : idOrNode;
      return api
        .fetchById(id)
        .then((full) => {
          applySelectNode(full, opts);
          setTree((prev) => {
            const clone = JSON.parse(JSON.stringify(prev));
            const existing = findNodeById(clone, full.id);
            if (existing) {
              existing.description = full.description;
              existing.updatedAt = full.updatedAt;
              existing.system = full.system;
            }
            return clone;
          });
          return full;
        })
        .catch((err) => {
          if (err.status === 404) setNotFoundDocId(id);
          else setDocLoadError({ status: err.status || 'network', docId: id });
          return null;
        });
    },
    [applySelectNode], // eslint-disable-line react-hooks/exhaustive-deps
  );

  // selectNode объявлен ниже — после navigateToDocById (нужен для doc-ссылок
  // на документы вне текущего дерева).

  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      if (onSearch) onSearch('', searchMode);
      return;
    }
    setSelectedNode(null);
    if (onSearch) onSearch(searchQuery, searchMode);
    await performSearch(searchQuery, searchMode);
  }, [searchQuery, searchMode, performSearch, onSearch]);

  // ── Navigate to a specific doc by ID (used by cross-component navigation) ──

  const navigateToDocById = useCallback(
    async (docId, opts = {}) => {
      if (!docId) return;

      // If the doc is already in the tree, still fetch the full document — the
      // tree node holds only a snippet, not the full description.
      const existing = findNodeById(tree, docId);
      if (existing) {
        fetchFullAndSelect(existing, opts);
        return;
      }

      // Otherwise load the ancestor chain and then select
      try {
        let currentTree = tree.length > 0 ? tree : [];
        if (currentTree.length === 0) {
          const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
          currentTree = Array.isArray(paged.items) ? paged.items : [];
          setTree(currentTree);
        }

        const ancestorIds = await api.fetchAncestors(docId);
        for (const ancestorId of ancestorIds) {
          // FULL_PAGE, not PAGE_SIZE: the target may sit on page 2+ of this
          // ancestor's children — a first-page fetch would omit it and the
          // findNodeById below would wrongly report it as not found.
          const paged = await api.fetchChildren(ancestorId, 0, FULL_PAGE);
          currentTree = await new Promise((resolve) => {
            setTree((prev) => {
              const clone = JSON.parse(JSON.stringify(prev));
              const parent = spliceChildren(clone, ancestorId, paged, { replace: true });
              if (parent) parent._openOnLoad = true;
              resolve(clone);
              return clone;
            });
          });
        }

        // A root-level target beyond the first page: reload the full root list.
        if (ancestorIds.length === 0 && !findNodeById(currentTree, docId)) {
          const fullRoot = await api.fetchChildren(null, 0, FULL_PAGE);
          currentTree = Array.isArray(fullRoot.items) ? fullRoot.items : currentTree;
          setTree(currentTree);
        }

        const target = findNodeById(currentTree, docId);
        if (target) {
          // Fetch the full document (the tree stub has only a snippet).
          // FolderDetail's useFolderChildren loads folder children separately
          // (deduplicated); no child fetch needed here.
          fetchFullAndSelect(target, opts);
        } else {
          setNotFoundDocId(docId);
        }
      } catch (err) {
        if (err.status === 404) {
          setNotFoundDocId(docId);
        } else {
          setDocLoadError({ status: err.status || 'network', docId });
        }
      }
    },
    [tree, fetchFullAndSelect],
  );

  /**
   * Выбор узла из дерева/результатов поиска, ИЛИ навигация по id (doc-ссылка).
   *   • Пришёл объект-узел → выбираем напрямую (он уже в дереве).
   *   • Пришёл id и узел есть в дереве → выбираем напрямую.
   *   • Пришёл id, узла в дереве нет → navigateToDocById грузит цепочку
   *     предков, раскрывает дерево и выбирает документ.
   * Во всех случаях notify:true — пользовательское действие, навигация
   * обновит URL.
   */
  const selectNode = useCallback(
    (nodeOrId) => {
      if (nodeOrId && typeof nodeOrId === 'object') {
        fetchFullAndSelect(nodeOrId, { notify: true });
        return;
      }
      const id = String(nodeOrId);
      const existing = findNodeById(tree, id);
      if (existing) {
        fetchFullAndSelect(existing, { notify: true });
      } else {
        // Документа нет в текущем дереве — грузим предков и раскрываем дерево.
        navigateToDocById(id, { notify: true });
      }
    },
    [tree, fetchFullAndSelect, navigateToDocById],
  );

  // ── Однократная загрузка корня дерева ────────────────────────────────────
  useEffect(() => {
    if (didInitRef.current) return;
    didInitRef.current = true;
    (async () => {
      try {
        const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
        setTree(Array.isArray(paged.items) ? paged.items : []);
      } catch {
        setTree([]);
      }
    })();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Реакция на навигацию (props из useAppNavigation) ─────────────────────
  // Единственный вход для «показать документ N» / «показать поиск Q».
  // Срабатывает при: открытии doc-ссылки в чате, клике вкладки «База знаний»
  // с последним docId, popstate (App обновляет props), прямой ссылке.
  // notify:false — выбор пришёл ИЗ навигации, обратно уведомлять не нужно.
  const lastNavDocRef = useRef(undefined);
  const lastNavSearchRef = useRef(undefined);

  useEffect(() => {
    if (navDocId) {
      if (lastNavDocRef.current === navDocId) return; // уже показан
      lastNavDocRef.current = navDocId;
      lastNavSearchRef.current = undefined;
      setActiveTab(navDocTab || 'summary');
      navigateToDocById(navDocId, { notify: false });
    } else if (navSearch) {
      lastNavDocRef.current = undefined;
      if (lastNavSearchRef.current === navSearch) return;
      lastNavSearchRef.current = navSearch;
      setSelectedNode(null);
      setSearchQuery(navSearch);
      setSearchMode(navMode || 'hybrid');
      performSearch(navSearch, navMode || 'hybrid');
    } else {
      // Навигация без doc/search → очистить выбор (напр. ушли в чистый KB).
      lastNavDocRef.current = undefined;
      lastNavSearchRef.current = undefined;
      setSelectedNode(null);
      setSearchResults([]);
      setSearchQuery('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navDocId, navSearch, navDocTab, navMode]);

  // Keep selectedNode in sync when the tree updates after CRUD.
  //
  // The tree holds only stubs (description is a ≤150-char snippet), so we must
  // NOT blindly replace the selected node with the tree version — that's what
  // made a freshly-refreshed document flash full and then collapse to the
  // truncated snippet. Instead we merge the tree's structural fields (title,
  // children, flags, updatedAt) into the current selection while keeping the
  // full `description` whenever the current selection is a complete document.
  useEffect(() => {
    if (!selectedNode) return;
    const fromTree = findNodeById(tree, selectedNode.id);
    if (!fromTree) return;

    setSelectedNode((prev) => {
      if (!prev || prev.id !== selectedNode.id) return prev;
      const keepFullDescription = prev._full;
      const merged = {
        ...prev,
        ...fromTree,
        // Preserve full content for a fully-loaded document; otherwise take the
        // tree's (possibly fresher) snippet.
        description: keepFullDescription ? prev.description : fromTree.description,
        // Don't let a childless stub wipe children we've already loaded.
        children: fromTree.children ?? prev.children,
        _full: prev._full,
      };
      return merged;
    });
  }, [tree]); // eslint-disable-line react-hooks/exhaustive-deps

  // (popstate вынесен в useAppNavigation — KB историю не слушает. Навигация
  // приходит через props navDocId/navSearch, см. эффект выше.)

  // ── CRUD ─────────────────────────────────────────────────────────────────────

  const handleCreate = async (body) => {
    try {
      const res = await api.create(body);
      if (res.ok) {
        setShowAddModal(false);
        await refreshScope(body.parentId ?? null);
      }
    } catch {
      /* noop */
    }
  };

  const handleUpdate = async (id, patch) => {
    // Optimistically update the local tree…
    setTree((prev) => updateNodeInTree(prev, id, patch));
    // …and the selected node directly, so the full-content detail reflects the
    // edit immediately. (The tree-sync effect preserves prev.description for a
    // fully-loaded doc, so it won't pick up the patch on its own.)
    setSelectedNode((prev) => (prev && prev.id === id ? { ...prev, ...patch } : prev));
    try {
      const res = await api.update(id, patch);
      if (!res.ok) throw new Error('Update failed');
    } catch (err) {
      console.error('Update error:', err);
      // Do NOT roll back — keep the user's edits in the UI, surface an error
      setSaveError({ message: 'Не удалось сохранить изменения. Попробуйте позже.', id });
    }
  };

  const handleRename = (id, title) => {
    if (title?.trim()) handleUpdate(id, { title: title.trim() });
  };

  const handleSummarize = useCallback(
    async (id) => {
      try {
        const updated = await api.summarize(id);
        // Patch summary fields into selected node and tree cache
        const patch = {
          summary: updated.summary,
          summaryStale: updated.summaryStale,
          summarySourceVersion: updated.summarySourceVersion,
        };
        setSelectedNode((prev) => (prev && prev.id === id ? { ...prev, ...patch } : prev));
        setTree((prev) => updateNodeInTree(prev, id, patch));
      } catch (err) {
        setSaveError({ message: err.message || 'Не удалось сгенерировать summary.' });
      }
    },
    [updateNodeInTree],
  );

  const handleDelete = async (id) => {
    if (!window.confirm('Удалить?')) return;
    try {
      const res = await api.delete(id);
      if (res.ok) {
        if (selectedNode?.id === id) setSelectedNode(null);
        const node = findNodeById(tree, id);
        await refreshScope(node?.parentId ?? null);
      }
    } catch {
      /* noop */
    }
  };

  // ── Reorder ───────────────────────────────────────────────────────────────────

  /** Optimistic UI update → PATCH → rollback on error. */
  const executeReorder = useCallback(
    async (dropInfo) => {
      const { draggedId, draggedParent, targetId, targetParent, position } = dropInfo;

      const newTree = applyReorder(tree, dropInfo);
      setTree(newTree);

      const affectedParent = position === 'inside' ? targetId : targetParent;
      const isMove = draggedParent !== affectedParent;

      try {
        // 1. Parent change: call the dedicated move endpoint first.
        //    It handles the cycle check and sets the new parentId in one transaction.
        if (isMove) {
          const moveRes = await api.moveToParent(draggedId, affectedParent ?? null);
          if (!moveRes.ok) {
            const body = await moveRes.json().catch(() => ({}));
            throw new Error(body.message || `Move failed: ${moveRes.status}`);
          }
        }

        // 2. Persist the visual order of the new sibling list.
        const orderedIds = getSiblings(newTree, affectedParent).map((n) => n.id);
        const reorderRes = await api.reorder(affectedParent, orderedIds);
        if (!reorderRes.ok) throw new Error('reorder failed');
      } catch (err) {
        console.error('Reorder error, rolling back:', err);
        setSaveError({ message: err.message || 'Не удалось переместить документ. Попробуйте позже.' });
        loadTree();
      }
    },
    [tree, loadTree],
  );

  /** Called by TreeNode on drop — confirms first if the parent changes. */
  const handleReorder = useCallback(
    (dropInfo) => {
      if (isParentChange(dropInfo)) {
        const newParentId = dropInfo.position === 'inside' ? dropInfo.targetId : dropInfo.targetParent;
        setMoveConfirm({
          dropInfo,
          fromTitle: parentTitle(tree, dropInfo.draggedParent),
          toTitle: parentTitle(tree, newParentId),
        });
      } else {
        executeReorder(dropInfo);
      }
    },
    [tree, executeReorder],
  );

  const handleMoveConfirm = () => {
    if (moveConfirm) executeReorder(moveConfirm.dropInfo);
    setMoveConfirm(null);
  };

  const handleMoveCancel = () => setMoveConfirm(null);

  const handleRefresh = useCallback(async () => {
    if (refreshing) return;
    setRefreshing(true);
    try {
      // 1. Reload root-level tree (first page — keeps lazy pagination for the
      //    common case where the selected node lives near the top).
      const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
      let currentTree = Array.isArray(paged.items) ? paged.items : [];
      setTree(currentTree);

      if (!selectedNode) return;

      // Splice a freshly-fetched page into a cloned tree and return the clone.
      // (Tracking the clone in a plain variable avoids the awkward
      // setTree-inside-a-Promise dance and keeps `currentTree` authoritative.)
      const splice = (treeArr, parentId, pagedChildren, { open = false } = {}) => {
        const clone = JSON.parse(JSON.stringify(treeArr));
        const parent = spliceChildren(clone, parentId, pagedChildren, { replace: true });
        if (parent && open) parent._openOnLoad = true;
        return clone;
      };

      try {
        const ancestorIds = (await api.fetchAncestors(selectedNode.id)) || [];

        // 2. Restore the path to the selected node. The node may sit on page 2+
        //    of its parent's children, which a PAGE_SIZE fetch would NOT include
        //    — it would then be missing from the tree, findNodeById below would
        //    fail, and the selection would be lost ("Выберите документ для
        //    просмотра"). So load the FULL child list for each ancestor.
        for (const ancestorId of ancestorIds) {
          const ancestorPaged = await api.fetchChildren(ancestorId, 0, FULL_PAGE);
          currentTree = splice(currentTree, ancestorId, ancestorPaged, { open: true });
          setTree(currentTree);
        }

        // 3. A root-level node beyond the first page needs the full root list too
        //    (it has no ancestors, so the loop above never runs for it).
        if (ancestorIds.length === 0 && !findNodeById(currentTree, selectedNode.id)) {
          const fullRoot = await api.fetchChildren(null, 0, FULL_PAGE);
          currentTree = Array.isArray(fullRoot.items) ? fullRoot.items : currentTree;
          setTree(currentTree);
        }

        const target = findNodeById(currentTree, selectedNode.id);
        if (target) {
          // For folders, also reload children (full list, so the tree shows them
          // all without a stray "load more").
          if (target.type === 'folder') {
            const folderPaged = await api.fetchChildren(selectedNode.id, 0, FULL_PAGE);
            currentTree = splice(currentTree, selectedNode.id, folderPaged);
            setTree(currentTree);
          }
          // Re-fetch the FULL document and re-select it. The tree reload above
          // replaced the node with a snippet-only stub; without this the
          // tree-sync effect would push that stub into the detail and the
          // description would appear truncated after a manual refresh.
          await fetchFullAndSelect(target);
        } else {
          setSelectedNode(null);
        }
      } catch {
        // If ancestors fail (deleted node?), just clear selection
        setSelectedNode(null);
      }
    } catch {
      // Tree reload failed — leave current state
    } finally {
      setRefreshing(false);
    }
  }, [refreshing, selectedNode, fetchFullAndSelect]);

  // ── Derived ────────────────────────────────────────────────────────────────
  const path = selectedNode ? findPath(tree, selectedNode.id) || [] : [];

  // Смена вкладки детали документа → локально + уведомить навигацию (URL).
  const changeTab = useCallback(
    (tab) => {
      setActiveTab(tab);
      if (onTabChange) onTabChange(tab);
    },
    [onTabChange],
  );

  return {
    // state
    tree,
    selectedNode,
    activeTab,
    searchQuery,
    searchMode,
    searchResults,
    showAddModal,
    notFoundDocId,
    docLoadError,
    saveError,
    refreshing,
    moveConfirm,
    path,
    // setters used directly by the view
    setActiveTab: changeTab,
    setSearchQuery,
    setSearchMode,
    setShowAddModal,
    setNotFoundDocId,
    setDocLoadError,
    setSaveError,
    // handlers
    handleLoadChildren,
    handleSearch,
    selectNode,
    handleCreate,
    handleUpdate,
    handleRename,
    handleSummarize,
    handleDelete,
    handleReorder,
    handleMoveConfirm,
    handleMoveCancel,
    handleRefresh,
  };
}
