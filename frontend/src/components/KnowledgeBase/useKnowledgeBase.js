import { useState, useEffect, useCallback, useRef } from 'react';

import api from './api';
import { getSiblings, applyReorder, parentTitle, isParentChange, updateNodeInTree, spliceChildren } from './treeOps';
import { findNodeById, findPath, getUrlState, setKBUrlState } from '../Utils/utils';

const PAGE_SIZE = 10;

/**
 * Encapsulates every piece of Knowledge Base state, side effect and handler.
 * The component consuming this hook is purely presentational.
 */
export default function useKnowledgeBase() {
  const [tree, setTree] = useState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [activeTab, setActiveTab] = useState('summary');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchMode, setSearchMode] = useState('hybrid');
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
  const applySelectNode = useCallback((node) => {
    // `_full` marks this as a complete document (from GET /documents/{id}),
    // so the tree-sync effect won't later overwrite its description with a
    // tree stub (which carries only a ≤150-char snippet).
    setSelectedNode({ ...node, _full: true });
    setActiveTab('summary');
    setSearchResults([]);
    setSearchQuery('');
    setNotFoundDocId(null);
    setDocLoadError(null);
    setKBUrlState(node.id, 'summary', null, null);

    // NOTE: folder children are loaded by FolderDetail's useFolderChildren
    // through the shared (deduplicated) loader. We intentionally do NOT fetch
    // them here — doing so fired a second, differently-sized request
    // (size=10 here vs size=1000 there) that couldn't be deduplicated.
  }, []);

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
    (idOrNode) => {
      const id = typeof idOrNode === 'object' ? idOrNode.id : idOrNode;
      return api
        .fetchById(id)
        .then((full) => {
          applySelectNode(full);
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

  const selectNode = useCallback(
    (node) => {
      fetchFullAndSelect(node);
    },
    [fetchFullAndSelect],
  );

  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      setKBUrlState(null, activeTab, null, null);
      return;
    }
    setSelectedNode(null);
    setKBUrlState(null, activeTab, searchQuery, searchMode);
    await performSearch(searchQuery, searchMode);
  }, [searchQuery, searchMode, activeTab, performSearch]);

  // ── Navigate to a specific doc by ID (used by cross-component navigation) ──

  const navigateToDocById = useCallback(
    async (docId) => {
      if (!docId) return;

      // If the doc is already in the tree, still fetch the full document — the
      // tree node holds only a snippet, not the full description.
      const existing = findNodeById(tree, docId);
      if (existing) {
        fetchFullAndSelect(existing);
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
          const paged = await api.fetchChildren(ancestorId, 0, PAGE_SIZE);
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

        const target = findNodeById(currentTree, docId);
        if (target) {
          // Fetch the full document (the tree stub has only a snippet).
          // FolderDetail's useFolderChildren loads folder children separately
          // (deduplicated); no child fetch needed here.
          fetchFullAndSelect(target);
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

  // ── Listen for cross-component navigation events ─────────────────────────

  useEffect(() => {
    const handleNavigateDoc = (e) => {
      const { docId } = e.detail || {};
      if (docId) navigateToDocById(docId);
    };
    window.addEventListener('app:navigate-doc', handleNavigateDoc);
    return () => window.removeEventListener('app:navigate-doc', handleNavigateDoc);
  }, [navigateToDocById]);

  // ── Restore from URL on first mount ─────────────────────────────────────────

  useEffect(() => {
    if (didInitRef.current) return;
    didInitRef.current = true;
    const init = async () => {
      let rootNodes = [];
      try {
        const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
        rootNodes = Array.isArray(paged.items) ? paged.items : [];
        setTree(rootNodes);
      } catch {
        setTree([]);
      }

      const { docId, docTab: tab, searchQuery: urlSearch, searchMode: urlMode } = getUrlState();
      setActiveTab(tab);

      if (docId) {
        // Fetch ancestor chain [rootId, ..., parentId] and load each level in order
        let currentTree = rootNodes;
        try {
          const ancestorIds = await api.fetchAncestors(docId);
          for (const ancestorId of ancestorIds) {
            const paged = await api.fetchChildren(ancestorId, 0, PAGE_SIZE);
            currentTree = await new Promise((resolve) => {
              setTree((prev) => {
                const clone = JSON.parse(JSON.stringify(prev));
                const parent = spliceChildren(clone, ancestorId, paged, { replace: true });
                if (parent) parent._openOnLoad = true; // signal TreeNode to open
                resolve(clone);
                return clone;
              });
            });
          }

          const target = findNodeById(currentTree, docId);
          if (target) {
            // Fetch the full document — the tree stub holds only a ≤150-char
            // snippet, which previously showed as a truncated description on
            // direct-link open. If it's a folder, FolderDetail's
            // useFolderChildren loads its children (deduplicated) separately.
            fetchFullAndSelect(target);
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
      } else if (urlSearch) {
        setSearchQuery(urlSearch);
        setSearchMode(urlMode);
        await performSearch(urlSearch, urlMode);
      }
    };
    init();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

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

  // Browser back/forward navigation
  useEffect(() => {
    const onPopState = async () => {
      const { docId, docTab: tab, searchQuery: urlSearch, searchMode: urlMode } = getUrlState();
      setActiveTab(tab);
      if (docId) {
        if (tree.length === 0) await loadTree();
        const node = findNodeById(tree, docId);
        if (node) {
          fetchFullAndSelect(node);
          setSearchResults([]);
          setSearchQuery('');
        } else {
          // Try loading the full path for this doc
          navigateToDocById(docId);
        }
      } else if (urlSearch) {
        setSearchQuery(urlSearch);
        setSearchMode(urlMode);
        setSelectedNode(null);
        await performSearch(urlSearch, urlMode);
      } else {
        setSelectedNode(null);
        setSearchResults([]);
        setSearchQuery('');
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [loadTree, tree, performSearch, navigateToDocById]);

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
      const orderedIds = getSiblings(newTree, affectedParent).map((n) => n.id);

      try {
        const res = await api.reorder(affectedParent, orderedIds);
        if (!res.ok) throw new Error('reorder failed');
        if (draggedParent !== affectedParent) {
          await api.update(draggedId, { parentId: affectedParent });
        }
      } catch (err) {
        console.error('Reorder error, rolling back:', err);
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
      // 1. Reload root-level tree
      const paged = await api.fetchChildren(null, 0, PAGE_SIZE);
      const rootNodes = Array.isArray(paged.items) ? paged.items : [];
      setTree(rootNodes);

      // 2. If a node is selected, reload its ancestor chain to restore tree path
      if (selectedNode) {
        let currentTree = rootNodes;
        try {
          const ancestorIds = await api.fetchAncestors(selectedNode.id);
          for (const ancestorId of ancestorIds) {
            const ancestorPaged = await api.fetchChildren(ancestorId, 0, PAGE_SIZE);
            currentTree = await new Promise((resolve) => {
              setTree((prev) => {
                const clone = JSON.parse(JSON.stringify(prev));
                const parent = spliceChildren(clone, ancestorId, ancestorPaged, { replace: true });
                if (parent) parent._openOnLoad = true;
                resolve(clone);
                return clone;
              });
            });
          }

          const target = findNodeById(currentTree, selectedNode.id);
          if (target) {
            // For folders, also reload children
            if (target.type === 'folder') {
              const folderPaged = await api.fetchChildren(selectedNode.id, 0, PAGE_SIZE);
              setTree((prev) => {
                const clone = JSON.parse(JSON.stringify(prev));
                spliceChildren(clone, selectedNode.id, folderPaged, { replace: true });
                return clone;
              });
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
      }
    } catch {
      // Tree reload failed — leave current state
    } finally {
      setRefreshing(false);
    }
  }, [refreshing, selectedNode, fetchFullAndSelect]);

  // ── Derived ────────────────────────────────────────────────────────────────
  const path = selectedNode ? findPath(tree, selectedNode.id) || [] : [];

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
    setActiveTab,
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
    handleDelete,
    handleReorder,
    handleMoveConfirm,
    handleMoveCancel,
    handleRefresh,
  };
}
