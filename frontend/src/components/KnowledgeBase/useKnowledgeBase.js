import { useState, useEffect, useCallback } from 'react';

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
   * Called by TreeNode when a folder is expanded or "load more" is clicked.
   */
  const handleLoadChildren = useCallback(async (parentId, page = 0, size = PAGE_SIZE) => {
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
    }
  }, []);

  /** Reloads a parent's children scope after a mutation (create/delete). */
  const refreshScope = useCallback(
    async (parentId) => {
      if (parentId === null) {
        await loadTree();
        return;
      }
      const paged = await api.fetchChildren(parentId, 0, 1000);
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
    (node) => {
      setSelectedNode(node);
      setActiveTab('summary');
      setSearchResults([]);
      setSearchQuery('');
      setNotFoundDocId(null);
      setDocLoadError(null);
      setKBUrlState(node.id, 'summary', null, null);

      // Auto-load children for folders so Summary/Contents tabs have data
      if (node.type === 'folder' && !node._childrenLoaded && (!node.children || node.children.length === 0)) {
        handleLoadChildren(node.id, 0, PAGE_SIZE);
      }
    },
    [handleLoadChildren],
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
  const selectNode = useCallback(
    (node) => {
      // Always fetch the full document — tree stubs contain only a snippet (≤150 chars),
      // not the full description needed by Summary/Content tabs.
      api
        .fetchById(node.id)
        .then((full) => {
          applySelectNode(full);
          // Patch the full data back into the tree cache so the tree-sync
          // effect (which runs on every tree update) doesn't clobber it.
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
        })
        .catch((err) => {
          if (err.status === 404) setNotFoundDocId(node.id);
          else setDocLoadError({ status: err.status || 'network', docId: node.id });
        });
    },
    [applySelectNode], // eslint-disable-line react-hooks/exhaustive-deps
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

      // If the doc is already in the tree, select it directly
      const existing = findNodeById(tree, docId);
      if (existing) {
        applySelectNode(existing);
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
          if (target.type === 'folder' && !target._childrenLoaded) {
            const paged = await api.fetchChildren(docId, 0, PAGE_SIZE);
            let enrichedTarget = target;
            setTree((prev) => {
              const clone = JSON.parse(JSON.stringify(prev));
              const folder = spliceChildren(clone, docId, paged, { replace: true });
              if (folder) enrichedTarget = folder;
              return clone;
            });
            setSelectedNode(enrichedTarget);
          } else {
            setSelectedNode(target);
          }
          setActiveTab('summary');
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
    [tree, applySelectNode],
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
            // For folders, also load children so FolderDetail renders Contents immediately
            if (target.type === 'folder' && !target._childrenLoaded) {
              const paged = await api.fetchChildren(docId, 0, PAGE_SIZE);
              let enrichedTarget = target;
              setTree((prev) => {
                const clone = JSON.parse(JSON.stringify(prev));
                const folder = spliceChildren(clone, docId, paged, { replace: true });
                if (folder) enrichedTarget = folder;
                return clone;
              });
              setSelectedNode(enrichedTarget);
            } else {
              setSelectedNode(target);
            }
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

  // Keep selectedNode in sync when the tree updates after CRUD
  useEffect(() => {
    if (!selectedNode) return;
    const updated = findNodeById(tree, selectedNode.id);
    if (updated) setSelectedNode(updated);
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
          setSelectedNode(node);
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
    // Optimistically update the local tree
    setTree((prev) => updateNodeInTree(prev, id, patch));
    try {
      const res = await api.update(id, patch);
      if (!res.ok) throw new Error('Update failed');
      // selectedNode is re-synced by the tree effect above
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
            // selectedNode will be re-synced by the tree effect
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
  }, [refreshing, selectedNode]);

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
