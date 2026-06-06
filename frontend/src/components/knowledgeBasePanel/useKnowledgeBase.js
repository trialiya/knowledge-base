import { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import api from './api';
import { getSiblings, applyReorder, parentTitle, isParentChange, updateNodeInTree, applyChildren } from './treeOps';
import { findNodeById, findPath } from '../common/utils';
import { isEditorDirty, clearEditorDirty } from './editorDirtyStore';

const PAGE_SIZE = 10;
// Used when we must guarantee the WHOLE child list is present (e.g. restoring
// the path to a selected node after refresh / direct-link navigation), so a
// node sitting on page 2+ of its parent isn't missing from the tree.
const FULL_PAGE = 1000;

const rootItems = (paged) => (Array.isArray(paged?.items) ? paged.items : []);

/**
 * Encapsulates every piece of Knowledge Base state, side effect and handler.
 * The component consuming this hook is purely presentational.
 *
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
  const { t } = useTranslation('knowledgeBase');
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

  // Pending delete awaiting user confirmation: { id, title, type }
  const [deleteConfirm, setDeleteConfirm] = useState(null);

  // ── Unsaved-changes guard ──────────────────────────────────────────────────
  // When the MarkdownEditor has unsaved edits, navigation actions (selecting
  // another doc, switching the detail tab, running a search) are deferred until
  // the user confirms discarding. The deferred action is stashed in a ref; a
  // boolean state drives the confirm modal.
  const [discardConfirm, setDiscardConfirm] = useState(false);
  const pendingActionRef = useRef(null);

  const guard = useCallback((action) => {
    if (isEditorDirty()) {
      pendingActionRef.current = action;
      setDiscardConfirm(true);
    } else {
      action();
    }
  }, []);

  const handleDiscardConfirm = useCallback(() => {
    clearEditorDirty();
    setDiscardConfirm(false);
    const action = pendingActionRef.current;
    pendingActionRef.current = null;
    if (action) action();
  }, []);

  const handleDiscardCancel = useCallback(() => {
    pendingActionRef.current = null;
    setDiscardConfirm(false);
  }, []);

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
      const items = rootItems(paged);
      setTree(items);
      return items;
    } catch {
      setTree([]);
      return [];
    }
  }, []);

  /**
   * Lazy-loads one page of children for a node and splices them into the tree.
   * Called by TreeNode when a folder is expanded or "load more" is clicked,
   * and by the folder detail (via the full-list loader).
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
        setTree((prev) => applyChildren(prev, parentId, paged, { replace: page === 0 }));
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
      setTree((prev) => applyChildren(prev, parentId, paged, { replace: true }));
    },
    [loadTree],
  );

  /**
   * Ensures `targetId` is present in the tree by loading the FULL child list of
   * every ancestor (and the root, for a first-pageless root node), marking each
   * ancestor `_openOnLoad` so the tree auto-expands the path. Threads the
   * growing tree through a local variable AND pushes each step to state so the
   * UI expands progressively. Returns the final tree.
   *
   * Shared by direct-link navigation and manual refresh — previously these were
   * two near-identical copies, one using a resolve-inside-setState hack.
   *
   * Throws if api.fetchAncestors fails (caller decides how to recover).
   */
  const materializePathTo = useCallback(async (targetId, baseTree) => {
    let cur = baseTree;
    const ancestorIds = (await api.fetchAncestors(targetId)) || [];

    for (const ancestorId of ancestorIds) {
      const paged = await api.fetchChildren(ancestorId, 0, FULL_PAGE);
      cur = applyChildren(cur, ancestorId, paged, { replace: true, open: true });
      setTree(cur);
    }

    // A root-level target beyond the first page has no ancestors, so reload the
    // full root list to bring it into the tree.
    if (ancestorIds.length === 0 && !findNodeById(cur, targetId)) {
      const fullRoot = await api.fetchChildren(null, 0, FULL_PAGE);
      cur = rootItems(fullRoot).length ? rootItems(fullRoot) : cur;
      setTree(cur);
    }

    return cur;
  }, []);

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

  // ── Selection ──────────────────────────────────────────────────────────────

  /**
   * Core selection logic — expects a full DocumentNode with `type` populated.
   * Separated from the fetch wrapper so it can be called after async enrichment.
   *
   * `_full` marks this as a complete document (from GET /documents/{id}), so the
   * tree-sync effect won't later overwrite its description with a tree stub
   * (which carries only a ≤150-char snippet).
   */
  const applySelectNode = useCallback(
    (node, { notify = true } = {}) => {
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
          setTree((prev) =>
            updateNodeInTree(prev, full.id, {
              description: full.description,
              updatedAt: full.updatedAt,
              system: full.system,
            }),
          );
          return full;
        })
        .catch((err) => {
          if (err.status === 404) setNotFoundDocId(id);
          else setDocLoadError({ status: err.status || 'network', docId: id });
          return null;
        });
    },
    [applySelectNode],
  );

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

  // ── Navigate to a specific doc by ID (cross-component navigation / links) ──

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

      try {
        // Seed the root if the tree is empty, then materialize the path.
        let baseTree = tree;
        if (baseTree.length === 0) {
          baseTree = await loadTree();
        }
        const cur = await materializePathTo(docId, baseTree);

        const target = findNodeById(cur, docId);
        if (target) {
          // Fetch the full document (the tree stub has only a snippet).
          // FolderDetail's useFolderChildren loads folder children separately
          // (deduplicated); no child fetch needed here.
          fetchFullAndSelect(target, opts);
        } else {
          setNotFoundDocId(docId);
        }
      } catch (err) {
        if (err.status === 404) setNotFoundDocId(docId);
        else setDocLoadError({ status: err.status || 'network', docId });
      }
    },
    [tree, fetchFullAndSelect, loadTree, materializePathTo],
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
        navigateToDocById(id, { notify: true });
      }
    },
    [tree, fetchFullAndSelect, navigateToDocById],
  );

  // ── Предупреждение при закрытии вкладки с несохранёнными правками ─────────
  useEffect(() => {
    const onBeforeUnload = (e) => {
      if (!isEditorDirty()) return undefined;
      e.preventDefault();
      e.returnValue = ''; // required for the native prompt in most browsers
      return '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  // ── Однократная загрузка корня дерева ────────────────────────────────────
  useEffect(() => {
    if (didInitRef.current) return;
    didInitRef.current = true;
    loadTree();
  }, [loadTree]);

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
      // Уход в chat-view приходит сюда как docId=null/search='' (App обнуляет
      // их, пока активна вкладка чата). Если в редакторе есть несохранённые
      // изменения — НЕ сбрасываем выбор: и selectedNode, и lastNavDocRef
      // остаются, поэтому DocumentDetail с редактором не размонтируется (он лишь
      // скрыт через CSS), а возврат в KB пройдёт по ветке «уже показан» и не
      // перезагрузит документ по API, сохранив правки.
      if (isEditorDirty()) return;
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
    setSelectedNode((prev) => (prev && prev.id === selectedNode.id ? mergeStubIntoSelection(prev, fromTree) : prev));
  }, [tree]); // eslint-disable-line react-hooks/exhaustive-deps

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
      setSaveError({ message: t('loadError.saveErrorMessage'), id });
    }
  };

  const handleRename = (id, title) => {
    if (title?.trim()) handleUpdate(id, { title: title.trim() });
  };

  const handleSummarize = useCallback(
    async (id) => {
      try {
        const updated = await api.summarize(id);
        const patch = {
          summary: updated.summary,
          summaryStale: updated.summaryStale,
          summarySourceVersion: updated.summarySourceVersion,
        };
        setSelectedNode((prev) => (prev && prev.id === id ? { ...prev, ...patch } : prev));
        setTree((prev) => updateNodeInTree(prev, id, patch));
      } catch (err) {
        setSaveError({ message: err.message || t('summary.generateError') });
      }
    },
    [t],
  );

  // Открывает модалку подтверждения удаления (вместо window.confirm).
  const handleDelete = (id) => {
    const node = findNodeById(tree, id);
    setDeleteConfirm({ id, title: node?.title ?? '', type: node?.type ?? 'document' });
  };

  // Реальное удаление — после подтверждения в модалке.
  const handleDeleteConfirm = async () => {
    const target = deleteConfirm;
    setDeleteConfirm(null);
    if (!target) return;
    try {
      const res = await api.delete(target.id);
      if (res.ok) {
        if (selectedNode?.id === target.id) setSelectedNode(null);
        const node = findNodeById(tree, target.id);
        await refreshScope(node?.parentId ?? null);
      }
    } catch {
      /* noop */
    }
  };

  const handleDeleteCancel = () => setDeleteConfirm(null);

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
        setSaveError({ message: err.message || t('loadError.moveErrorMessage') });
        loadTree();
      }
    },
    [tree, loadTree, t],
  );

  /** Called by TreeNode on drop — confirms first if the parent changes. */
  const handleReorder = useCallback(
    (dropInfo) => {
      if (isParentChange(dropInfo)) {
        const newParentId = dropInfo.position === 'inside' ? dropInfo.targetId : dropInfo.targetParent;
        setMoveConfirm({
          dropInfo,
          fromTitle: parentTitle(tree, dropInfo.draggedParent, t),
          toTitle: parentTitle(tree, newParentId, t),
        });
      } else {
        executeReorder(dropInfo);
      }
    },
    [tree, executeReorder, t],
  );

  const handleMoveConfirm = () => {
    if (moveConfirm) executeReorder(moveConfirm.dropInfo);
    setMoveConfirm(null);
  };

  const handleMoveCancel = () => setMoveConfirm(null);

  // ── Refresh ─────────────────────────────────────────────────────────────────
  // Re-fetches the root, restores the path to the selected node, then re-selects
  // the FULL document. Shares materializePathTo with direct-link navigation.
  const handleRefresh = useCallback(async () => {
    if (refreshing) return;
    setRefreshing(true);
    try {
      let cur = await loadTree();
      if (!selectedNode) return;

      try {
        cur = await materializePathTo(selectedNode.id, cur);

        const target = findNodeById(cur, selectedNode.id);
        if (!target) {
          setSelectedNode(null);
          return;
        }

        // For folders, also reload children (full list, so the tree shows them
        // all without a stray "load more").
        if (target.type === 'folder') {
          const folderPaged = await api.fetchChildren(selectedNode.id, 0, FULL_PAGE);
          cur = applyChildren(cur, selectedNode.id, folderPaged, { replace: true });
          setTree(cur);
        }

        // Re-fetch the FULL document and re-select it. The tree reload replaced
        // the node with a snippet-only stub; without this the tree-sync effect
        // would push that stub into the detail and the description would appear
        // truncated after a manual refresh.
        await fetchFullAndSelect(target);
      } catch {
        // If ancestors fail (deleted node?), just clear selection.
        setSelectedNode(null);
      }
    } catch {
      // Tree reload failed — leave current state.
    } finally {
      setRefreshing(false);
    }
  }, [refreshing, selectedNode, loadTree, materializePathTo, fetchFullAndSelect]);

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

  // ── Guarded navigation ─────────────────────────────────────────────────────
  // Public navigation entry points are wrapped so that, with unsaved editor
  // changes, the action is deferred behind the discard-confirm modal.
  const guardedSelectNode = useCallback((nodeOrId) => guard(() => selectNode(nodeOrId)), [guard, selectNode]);
  const guardedChangeTab = useCallback((tab) => guard(() => changeTab(tab)), [guard, changeTab]);
  const guardedSearch = useCallback(() => guard(() => handleSearch()), [guard, handleSearch]);
  // Refresh re-fetches the selected document from the API and remounts the
  // editor, so unsaved edits ARE discarded — hence the same discard-confirm.
  const guardedRefresh = useCallback(() => guard(() => handleRefresh()), [guard, handleRefresh]);

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
    deleteConfirm,
    discardConfirm,
    path,
    // setters used directly by the view
    setActiveTab: guardedChangeTab,
    setSearchQuery,
    setSearchMode,
    setShowAddModal,
    setNotFoundDocId,
    setDocLoadError,
    setSaveError,
    // handlers
    handleLoadChildren,
    handleSearch: guardedSearch,
    selectNode: guardedSelectNode,
    handleCreate,
    handleUpdate,
    handleRename,
    handleSummarize,
    handleDelete,
    handleDeleteConfirm,
    handleDeleteCancel,
    handleReorder,
    handleMoveConfirm,
    handleMoveCancel,
    handleRefresh: guardedRefresh,
    handleDiscardConfirm,
    handleDiscardCancel,
  };
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Merges a tree stub into the current selection. Centralizes the "don't let a
 * ≤150-char snippet clobber a fully-loaded document" rule that used to live
 * inline in the tree-sync effect.
 */
function mergeStubIntoSelection(prev, fromTree) {
  const keepFullDescription = prev._full;
  return {
    ...prev,
    ...fromTree,
    // Preserve full content for a fully-loaded document; otherwise take the
    // tree's (possibly fresher) snippet.
    description: keepFullDescription ? prev.description : fromTree.description,
    // Don't let a childless stub wipe children we've already loaded.
    children: fromTree.children ?? prev.children,
    _full: prev._full,
  };
}
