import React, { useEffect, useRef } from 'react';
import './KnowledgeBase.css';

import TreeNode from './TreeNode';
import FolderDetail from './FolderDetail';
import DocumentDetail from './DocumentDetail';
import AddModal from './AddModal';
import MoveConfirmModal from './MoveConfirmModal';
import ConfirmModal from '../common/ConfirmModal';
import SearchResults from './SearchResults';
import ErrorModal from '../common/ErrorModal';
import { IconPlus } from './icons';
import useKnowledgeBase from './useKnowledgeBase';

const KnowledgeBase = ({
  onNavigateToChat,
  isActive,
  docId,
  docTab,
  search,
  mode,
  refreshSignal,
  onRefreshingChange,
  onOpenDoc,
  onTabChange,
  onSearch,
}) => {
  const {
    tree,
    selectedNode,
    activeTab,
    searchQuery,
    searchResults,
    showAddModal,
    notFoundDocId,
    docLoadError,
    saveError,
    moveConfirm,
    deleteConfirm,
    discardConfirm,
    refreshing,
    path,
    setActiveTab,
    setShowAddModal,
    setNotFoundDocId,
    setDocLoadError,
    setSaveError,
    handleLoadChildren,
    selectNode,
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
    handleRefresh,
    handleDiscardConfirm,
    handleDiscardCancel,
  } = useKnowledgeBase({ docId, docTab, search, mode, onOpenDoc, onTabChange, onSearch });

  // ── Refresh, инициированный кнопкой в шапке вкладок (App.js) ────────────────
  // App инкрементит refreshSignal; первый «холостой» рендер пропускаем.
  const firstRefreshSignal = useRef(true);
  useEffect(() => {
    if (firstRefreshSignal.current) {
      firstRefreshSignal.current = false;
      return;
    }
    handleRefresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshSignal]);

  // Сообщаем статус refreshing наверх — для спиннера/блокировки кнопки.
  useEffect(() => {
    if (onRefreshingChange) onRefreshingChange(refreshing);
  }, [refreshing, onRefreshingChange]);

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
      <div className="kb-main">
        {/* ── Tree ── */}
        <div className="kb-tree">
          {/* Кнопка создания — в стиле «+ Новый чат» из списка чатов */}
          <button className="kb-new-doc-button" onClick={() => setShowAddModal(true)}>
            <IconPlus />
            Создать
          </button>

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

      {/* ── Unsaved-changes warning ── */}
      <ConfirmModal
        open={discardConfirm}
        icon="✏️"
        title="Несохранённые изменения"
        message="В редакторе есть несохранённые изменения. Если продолжить, они будут потеряны."
        confirmLabel="Продолжить без сохранения"
        cancelLabel="Остаться"
        onConfirm={handleDiscardConfirm}
        onCancel={handleDiscardCancel}
      />

      {/* ── Delete confirmation modal ── */}
      <ConfirmModal
        open={!!deleteConfirm}
        icon="🗑️"
        title={deleteConfirm?.type === 'folder' ? 'Удалить папку?' : 'Удалить документ?'}
        message={
          deleteConfirm?.title
            ? `«${deleteConfirm.title}» будет удалён без возможности восстановления.`
            : 'Элемент будет удалён без возможности восстановления.'
        }
        confirmLabel="Удалить"
        cancelLabel="Отмена"
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
      />

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
