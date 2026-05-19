import React, { useState, useEffect, useCallback, useRef } from 'react';
import './KnowledgeBase.css';

import TreeNode from './TreeNode';
import FolderDetail from './FolderDetail';
import DocumentDetail from './DocumentDetail';
import AddModal from './AddModal';
import SearchResults from './SearchResults';
import { IconPlus } from './icons';
import { findNodeById, findPath, getUrlState, setUrlState } from './utils';

// ─── API helpers ──────────────────────────────────────────────────────────────

const api = {
  fetchTree: () => fetch('/api/documents/tree').then((r) => r.json()),
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
};

// ─── Component ────────────────────────────────────────────────────────────────

const KnowledgeBase = () => {
  const [tree, setTree] = useState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [activeTab, setActiveTab] = useState('summary');
  const [searchQuery, setSearchQuery] = useState('');
  const [searchMode, setSearchMode] = useState('hybrid');
  const [searchResults, setSearchResults] = useState([]);
  const [showAddModal, setShowAddModal] = useState(false);

  const initialLoadDone = useRef(false);

  // ── Tree loading ─────────────────────────────────────────────────────────

  const loadTree = useCallback(async () => {
    try {
      const data = await api.fetchTree();
      setTree(Array.isArray(data) ? data : []);
    } catch {
      setTree([]);
    }
  }, []);

  // ── Search function ──────────────────────────────────────────────────────

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

  const clearSearchAndShowNode = useCallback(
    (node) => {
      selectNode(node);
    },
    [selectNode],
  );

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

  // ── Restore state from URL on mount ──────────────────────────────────────

  useEffect(() => {
    const restoreFromUrl = async () => {
      const { docId, tab, searchQuery: urlSearch, searchMode: urlMode } = getUrlState();
      setActiveTab(tab);
      if (docId) {
        // Ждём загрузки дерева, затем выбираем документ
        if (tree.length === 0) await loadTree();
        const node = findNodeById(tree, docId);
        if (node) {
          setSelectedNode(node);
          setSearchQuery('');
          setSearchResults([]);
        } else {
          setSelectedNode(null);
        }
      } else if (urlSearch) {
        setSearchQuery(urlSearch);
        setSearchMode(urlMode);
        setSelectedNode(null);
        await performSearch(urlSearch, urlMode);
      }
    };
    restoreFromUrl();
  }, [loadTree, tree, performSearch]);

  // Загружаем дерево при монтировании
  useEffect(() => {
    loadTree();
  }, [loadTree]);

  // Синхронизация выбранного узла после обновления дерева
  useEffect(() => {
    if (!selectedNode) return;
    const updated = findNodeById(tree, selectedNode.id);
    if (updated) setSelectedNode(updated);
  }, [tree, selectedNode]);

  // ── Обработчик кнопки «Назад»/«Вперёд» ────────────────────────────────────

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
        loadTree();
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
        loadTree();
      }
    } catch {
      /* noop */
    }
  };

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
    </div>
  );
};

export default KnowledgeBase;
