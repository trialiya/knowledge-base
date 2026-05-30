import React from 'react';
import './KnowledgeBase.css';

import TreeNode from './TreeNode';
import FolderDetail from './FolderDetail';
import DocumentDetail from './DocumentDetail';
import AddModal from './AddModal';
import MoveConfirmModal from './MoveConfirmModal';
import SearchResults from './SearchResults';
import ErrorModal from '../common/ErrorModal';
import { IconPlus, IconRefresh } from './icons';
import useKnowledgeBase from './useKnowledgeBase';

const KnowledgeBase = ({
  onNavigateToChat,
  isActive,
  docId,
  docTab,
  search,
  mode,
  onOpenDoc,
  onTabChange,
  onSearch,
}) => {
  const {
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
    moveConfirm,
    refreshing,
    path,
    setActiveTab,
    setSearchQuery,
    setSearchMode,
    setShowAddModal,
    setNotFoundDocId,
    setDocLoadError,
    setSaveError,
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
  } = useKnowledgeBase({ docId, docTab, search, mode, onOpenDoc, onTabChange, onSearch });

  const detailProps = {
    node: selectedNode,
    path,
    tab: activeTab,
    onTabChange: setActiveTab,
    onUpdate: handleUpdate,
    onDelete: handleDelete,
    onNavigate: selectNode,
    onRename: handleRename,
    onSummarize: handleSummarize,
    onLoadChildren: handleLoadChildren,
    tree,
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
        <div className="kb-header__actions">
          <button className="add-doc-btn" onClick={() => setShowAddModal(true)} title="Добавить">
            <IconPlus />
          </button>
          <button
            className={`add-doc-btn${refreshing ? ' kb-refresh-btn--spinning' : ''}`}
            onClick={handleRefresh}
            disabled={refreshing}
            title="Обновить"
          >
            <IconRefresh />
          </button>
        </div>
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
            <SearchResults query={searchQuery} results={searchResults} tree={tree} onSelect={selectNode} />
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

      {/* ── Document load error modal ── */}
      <ErrorModal
        open={!!notFoundDocId || !!docLoadError}
        icon={notFoundDocId ? '🔍' : '⚠️'}
        title={notFoundDocId ? 'Документ не найден' : 'Не удалось загрузить документ'}
        message={
          notFoundDocId
            ? 'Возможно, он был удалён или ссылка устарела.'
            : `Ошибка при загрузке документа${
                docLoadError && docLoadError.status !== 'network' ? ` (${docLoadError.status})` : ''
              }. Попробуйте позже.`
        }
        onClose={() => {
          setNotFoundDocId(null);
          setDocLoadError(null);
        }}
      />

      {/* ── Save error modal ── */}
      <ErrorModal
        open={!!saveError}
        icon="⚠️"
        title="Ошибка сохранения"
        message={saveError?.message || 'Не удалось сохранить изменения. Попробуйте позже.'}
        onClose={() => setSaveError(null)}
      />
    </div>
  );
};

export default KnowledgeBase;
