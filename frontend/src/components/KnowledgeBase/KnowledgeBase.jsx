import React, { useState, useEffect, useCallback, useRef } from 'react';
import './KnowledgeBase.css';

import TreeNode from './TreeNode';
import FolderDetail from './FolderDetail';
import DocumentDetail from './DocumentDetail';
import AddModal from './AddModal';
import MoveConfirmModal from './MoveConfirmModal';
import SearchResults from './SearchResults';
import { IconPlus } from './icons';
import { findNodeById, findPath, getUrlState, setUrlState } from './utils';

// ─── API helpers ──────────────────────────────────────────────────────────────

const api = {
  fetchTree: () => fetch('/api/documents/tree').then((r) => r.json()),
  fetchChildren: (parentId) =>
    fetch(`/api/documents/children${parentId ? `?parentId=${parentId}` : ''}`).then((r) => r.json()),
  fetchAncestors: (id) => fetch(`/api/documents/${id}/ancestors`).then((r) => r.json()),
  search: (q, mode) => fetch(`/api/search?q=${encodeURIComponent(q)}&mode=${mode}`).then((r) => r.json()),
  create: (body) =>
    fetch('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  update: (id, patch) =>
    fetch(`/api/documents/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    }),
  delete: (id) => fetch(`/api/documents/${id}`, { method: 'DELETE' }),
  reorder: (parentId, orderedIds) =>
    fetch('/api/documents/reorder', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId, orderedIds }),
    }),
};

// ─── Pure tree helpers ────────────────────────────────────────────────────────

function getSiblings(tree, parentId) {
  if (parentId === null) return tree;
  const parent = findNodeById(tree, parentId);
  return parent?.children ?? [];
}

function applyReorder(tree, { draggedId, draggedParent, targetId, targetParent, position }) {
  const clone = JSON.parse(JSON.stringify(tree));

  const getChildren = (parentId) => {
    if (parentId === null) return clone;
    return findNodeById(clone, parentId)?.children ?? [];
  };

  const srcList = getChildren(draggedParent);
  const dragIdx = srcList.findIndex((n) => n.id === draggedId);
  if (dragIdx === -1) return tree;

  const [dragged] = srcList.splice(dragIdx, 1);

  if (position === 'inside') {
    const targetNode = findNodeById(clone, targetId);
    if (!targetNode || targetNode.type !== 'folder') return tree;
    targetNode.children = targetNode.children ?? [];
    dragged.parentId = targetId;
    targetNode.children.push(dragged);
  } else {
    const dstList = getChildren(targetParent);
    const targetIdx = dstList.findIndex((n) => n.id === targetId);
    if (targetIdx === -1) return tree;
    dragged.parentId = targetParent;
    const insertAt = position === 'before' ? targetIdx : targetIdx + 1;
    dstList.splice(insertAt, 0, dragged);
  }

  return clone;
}

/** Display name for a parent node (null = root). */
function parentTitle(tree, parentId) {
  if (parentId === null) return 'Корневой уровень';
  return findNodeById(tree, parentId)?.title ?? 'Неизвестно';
}

/** True when a drop changes the node's parent. */
function isParentChange({ draggedParent, targetParent, position, targetId }) {
  if (position === 'inside') return draggedParent !== targetId;
  return draggedParent !== targetParent;
}

// ─── Component ────────────────────────────────────────────────────────────────

const KnowledgeBase = () => {
  const [tree, setTree] = useState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [activeTab, setActiveTab] = useState('summary');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchMode, setSearchMode] = useState('hybrid');
  const [searchResults, setSearchResults] = useState([]);
  const [showAddModal, setShowAddModal] = useState(false);

  // ── Move confirmation modal state ──────────────────────────────────────
  // Holds the pending drop info while we wait for user confirmation.
  const [moveConfirm, setMoveConfirm] = useState(null);
  // moveConfirm shape: { dropInfo, fromTitle, toTitle }

  const initialLoadDone = useRef(false);

  // ── Tree loading ─────────────────────────────────────────────────────────

  const loadTree = useCallback(async () => {
    try {
      const data = await api.fetchChildren(null); // root level only
      setTree(Array.isArray(data) ? data : []);
    } catch {
      setTree([]);
    }
  }, []);

  /**
   * Lazy-loads children of a node and splices them into the tree.
   * Called by TreeNode when user expands a folder.
   */
  const handleLoadChildren = useCallback(async (parentId) => {
    try {
      const children = await api.fetchChildren(parentId);
      setTree((prev) => {
        const clone = JSON.parse(JSON.stringify(prev));
        const parent = findNodeById(clone, parentId);
        if (parent) {
          parent.children = Array.isArray(children) ? children : [];
          parent._childrenLoaded = true;
        }
        return clone;
      });
    } catch {
      /* noop */
    }
  }, []);

  // ── Search ───────────────────────────────────────────────────────────────

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

  const selectNode = useCallback((node) => {
    setSelectedNode(node);
    setActiveTab('summary');
    setSearchResults([]);
    setSearchQuery('');
    setUrlState(node.id, 'summary', null, null);
  }, []);

  const clearSearchAndShowNode = useCallback((node) => selectNode(node), [selectNode]);

  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      setSearchResults([]);
      setUrlState(null, activeTab, null, null);
      return;
    }
    setSelectedNode(null);
    setUrlState(null, activeTab, searchQuery, searchMode);
    await performSearch(searchQuery, searchMode);
  }, [searchQuery, searchMode, activeTab, performSearch]);

  // ── Restore from URL ──────────────────────────────────────────────────────

  useEffect(() => {
    const init = async () => {
      // Load root nodes
      let rootNodes = [];
      try {
        const data = await api.fetchChildren(null);
        rootNodes = Array.isArray(data) ? data : [];
        setTree(rootNodes);
      } catch {
        setTree([]);
      }

      const { docId, tab, searchQuery: urlSearch, searchMode: urlMode } = getUrlState();
      setActiveTab(tab);

      if (docId) {
        // Fetch ancestor chain [rootId, ..., parentId] and load each level in order
        let currentTree = rootNodes;
        try {
          const ancestorIds = await api.fetchAncestors(docId);
          // Load and splice each ancestor level into the tree
          for (const ancestorId of ancestorIds) {
            const children = await api.fetchChildren(ancestorId);
            currentTree = await new Promise((resolve) => {
              setTree((prev) => {
                const clone = JSON.parse(JSON.stringify(prev));
                const parent = findNodeById(clone, ancestorId);
                if (parent) {
                  parent.children = Array.isArray(children) ? children : [];
                  parent._childrenLoaded = true;
                  parent.hasChildren = children.length > 0;
                  parent._openOnLoad = true; // signal TreeNode to open
                }
                resolve(clone);
                return clone;
              });
            });
          }
          // Find the target node in the fully-loaded tree
          const target = findNodeById(currentTree, docId);
          if (target) setSelectedNode(target);
        } catch {
          // fallback: just try root
          const target = findNodeById(rootNodes, docId);
          if (target) setSelectedNode(target);
        }
      } else if (urlSearch) {
        setSearchQuery(urlSearch);
        setSearchMode(urlMode);
        await performSearch(urlSearch, urlMode);
      }
    };
    init();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Sync selectedNode when tree updates after CRUD
  useEffect(() => {
    if (!selectedNode) return;
    const updated = findNodeById(tree, selectedNode.id);
    if (updated) setSelectedNode(updated);
  }, [tree]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const onPopState = async () => {
      const { docId, tab, searchQuery: urlSearch, searchMode: urlMode } = getUrlState();
      setActiveTab(tab);
      if (docId) {
        if (tree.length === 0) await loadTree();
        const node = findNodeById(tree, docId);
        if (node) {
          setSelectedNode(node);
          setSearchResults([]);
          setSearchQuery('');
        } else {
          setSelectedNode(null);
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
  }, [loadTree, tree, performSearch]);

  // ── CRUD ─────────────────────────────────────────────────────────────────

  const handleCreate = async (body) => {
    try {
      const res = await api.create(body);
      if (res.ok) {
        setShowAddModal(false);
        // Reload the parent scope of the new node
        const parentId = body.parentId ?? null;
        if (parentId === null) {
          loadTree(); // reload roots
        } else {
          // Invalidate that folder's children in the tree
          const children = await api.fetchChildren(parentId);
          setTree((prev) => {
            const clone = JSON.parse(JSON.stringify(prev));
            const parent = findNodeById(clone, parentId);
            if (parent) {
              parent.children = Array.isArray(children) ? children : [];
              parent._childrenLoaded = true;
              parent.hasChildren = children.length > 0;
            }
            return clone;
          });
        }
      }
    } catch {
      /* noop */
    }
  };

  const handleUpdate = async (id, patch) => {
    try {
      const res = await api.update(id, patch);
      if (res.ok) loadTree();
    } catch {
      /* noop */
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
        // Find the node's parentId before removing from tree
        const node = findNodeById(tree, id);
        const parentId = node?.parentId ?? null;
        if (parentId === null) {
          loadTree();
        } else {
          const children = await api.fetchChildren(parentId);
          setTree((prev) => {
            const clone = JSON.parse(JSON.stringify(prev));
            const parent = findNodeById(clone, parentId);
            if (parent) {
              parent.children = Array.isArray(children) ? children : [];
              parent._childrenLoaded = true;
              parent.hasChildren = children.length > 0;
            }
            return clone;
          });
        }
      }
    } catch {
      /* noop */
    }
  };

  // ── Reorder ───────────────────────────────────────────────────────────────

  /**
   * Executes the actual reorder: optimistic UI update → PATCH → rollback on error.
   */
  const executeReorder = useCallback(
    async (dropInfo) => {
      const { draggedId, draggedParent, targetId, targetParent, position } = dropInfo;

      const newTree = applyReorder(tree, dropInfo);
      setTree(newTree);

      const affectedParent = position === 'inside' ? targetId : targetParent;
      const siblings = getSiblings(newTree, affectedParent);
      const orderedIds = siblings.map((n) => n.id);

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

  /**
   * Called by TreeNode on drop.
   * If the drop changes the node's parent → show confirmation modal first.
   * Otherwise execute immediately.
   */
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

  // ── Render ───────────────────────────────────────────────────────────────

  const path = selectedNode ? findPath(tree, selectedNode.id) || [] : [];

  const detailProps = {
    node: selectedNode,
    path,
    tab: activeTab,
    onTabChange: setActiveTab,
    onUpdate: handleUpdate,
    onDelete: handleDelete,
    onNavigate: selectNode,
    onRename: handleRename,
  };

  return (
    <div className="knowledge-base-container">
      {/* ── Header ── */}
      <div className="kb-header">
        <span className="kb-header__title">Knowledge Base</span>
        <div className="kb-search-row">
          <input
            type="text"
            placeholder="Поиск... (Enter)"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
          />
          <select value={searchMode} onChange={(e) => setSearchMode(e.target.value)}>
            <option value="hybrid">Гибридный</option>
            <option value="semantic">Семантический</option>
            <option value="keyword">По словам</option>
          </select>
        </div>
        <button className="add-doc-btn" onClick={() => setShowAddModal(true)} title="Добавить">
          <IconPlus />
        </button>
      </div>

      <div className="kb-main">
        {/* ── Tree ── */}
        <div className="kb-tree">
          <div className="tree-container">
            {tree.map((node) => (
              <TreeNode
                key={node.id}
                node={node}
                level={0}
                selectedId={selectedNode?.id}
                onSelect={selectNode}
                onDelete={handleDelete}
                onReorder={handleReorder}
                onLoadChildren={handleLoadChildren}
              />
            ))}
          </div>
        </div>

        {/* ── Detail / Search Results ── */}
        <div className="kb-preview">
          {searchResults.length > 0 && !selectedNode ? (
            <SearchResults query={searchQuery} results={searchResults} tree={tree} onSelect={clearSearchAndShowNode} />
          ) : selectedNode ? (
            selectedNode.type === 'folder' ? (
              <FolderDetail key={selectedNode.id} {...detailProps} />
            ) : (
              <DocumentDetail key={selectedNode.id} {...detailProps} />
            )
          ) : (
            <div className="empty-preview">Выберите документ для просмотра</div>
          )}
        </div>
      </div>

      {showAddModal && (
        <AddModal
          tree={tree}
          defaultParentId={
            !selectedNode ? null : selectedNode.type === 'folder' ? selectedNode.id : selectedNode.parentId ?? null
          }
          onClose={() => setShowAddModal(false)}
          onCreate={handleCreate}
        />
      )}

      {/* ── Move confirmation modal ── */}
      {moveConfirm && (
        <MoveConfirmModal
          draggedTitle={moveConfirm.dropInfo.draggedTitle}
          fromTitle={moveConfirm.fromTitle}
          toTitle={moveConfirm.toTitle}
          onConfirm={handleMoveConfirm}
          onCancel={handleMoveCancel}
        />
      )}
    </div>
  );
};

export default KnowledgeBase;
