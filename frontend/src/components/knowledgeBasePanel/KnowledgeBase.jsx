import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import './KnowledgeBase.css';

import TreeNode from './TreeNode';
import TreeSearch from './TreeSearch';
import FolderDetail from './FolderDetail';
import DocumentDetail from './DocumentDetail';
import DetailModals from './DetailModals';
import AddModal from './AddModal';
import MoveConfirmModal from './MoveConfirmModal';
import ConfirmModal from '../common/modal/ConfirmModal';
import SearchResults from './SearchResults';
import ErrorModal from '../common/modal/ErrorModal';
import WorkspaceLayout from '../common/layout/WorkspaceLayout';
import { IconPlus } from '../../icons';
import { OWNER_TYPE } from '../../constants/ownerType';
import useListNavigation from '../common/search/useListNavigation';
import useKnowledgeBase from './useKnowledgeBase';
import useDetailPanel from './useDetailPanel';
import useAttachmentCount from '../common/attachments/useAttachmentCount';
import useFolderChildren from './useFolderChildren';
import { buildDetailTabs } from './detailSidebar';

const KnowledgeBase = ({
  docId,
  search,
  mode,
  refreshSignal,
  onRefreshingChange,
  onOpenDoc,
  onSearch,
  mutatedDocs,
  panels,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const handleTreeKeyDown = useListNavigation();

  const {
    tree,
    treeLoaded,
    selectedNode,
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
  } = useKnowledgeBase({ docId, search, mode, onOpenDoc, onSearch, mutatedDocs });

  // Состояние детали (черновик описания, полноэкранный режим, история) поднято
  // сюда: его делят ЦЕНТР (редактор) и ПРАВАЯ панель (описание, вложения) —
  // раньше оно жило внутри одной вкладки.
  const { fullscreen, setFullscreen, showHistory, setShowHistory, contentDraft, setContentDraft } = useDetailPanel(
    selectedNode?.description || '',
    selectedNode?.id ?? null,
  );

  // Счётчик вложений для бейджа: панель вложений смонтирована, только когда её
  // вкладка раскрыта, поэтому число берём отдельным запросом.
  const [attachmentCount, setAttachmentCount] = useAttachmentCount(OWNER_TYPE.DOCUMENT, selectedNode?.id ?? null);

  // Состав папки нужен правой панели, поэтому грузим его здесь, а не в FolderDetail.
  const { children: folderChildren } = useFolderChildren(selectedNode, handleLoadChildren);

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
    onUpdate: handleUpdate,
    onDelete: handleDelete,
    onNavigate: selectNode,
    onRename: handleRename,
    tree,
    contentDraft,
    setContentDraft,
    onExpandContent: () => setFullscreen(true),
    onHistory: () => setShowHistory(true),
  };

  const center =
    searchResults.length > 0 && !selectedNode ? (
      <SearchResults query={searchQuery} results={searchResults} tree={tree} onSelect={selectNode} />
    ) : selectedNode ? (
      selectedNode.type === 'folder' ? (
        <FolderDetail key={selectedNode.id} {...detailProps} />
      ) : (
        <DocumentDetail key={selectedNode.id} {...detailProps} />
      )
    ) : (
      <div className="empty-preview">{t('empty.selectDocument')}</div>
    );

  // Правая панель осмысленна только когда узел выбран: у результатов поиска и у
  // пустого экрана рассказывать не о чем — тогда её (и рельс) просто нет.
  const rightTabs = selectedNode
    ? buildDetailTabs({
        node: selectedNode,
        t,
        onNavigate: selectNode,
        onSummarize: handleSummarize,
        attachmentCount,
        onAttachmentCountChange: setAttachmentCount,
        folderChildren,
      })
    : null;

  return (
    <>
      <WorkspaceLayout
        className="workspace--kb"
        {...panels}
        left={{
          title: t('tree.title'),
          action: (
            <button type="button" className="btn btn--primary" onClick={() => setShowAddModal(true)}>
              <IconPlus />
              {t('tree.create')}
            </button>
          ),
          toolbar: <TreeSearch onSelect={selectNode} />,
          children:
            treeLoaded && tree.length === 0 ? (
              <div className="ws-hint">{t('tree.empty')}</div>
            ) : (
              <div
                className="ws-list"
                role="tree"
                aria-label={t('tree.title')}
                tabIndex={0}
                onKeyDown={handleTreeKeyDown}
              >
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
            ),
        }}
        center={center}
        right={rightTabs}
      />

      {/* ── Полноэкранный редактор и история ── */}
      {selectedNode && (
        <DetailModals
          node={selectedNode}
          fullscreen={fullscreen}
          onCloseFullscreen={() => setFullscreen(false)}
          showHistory={showHistory}
          onCloseHistory={() => setShowHistory(false)}
          onUpdate={handleUpdate}
          contentDraft={contentDraft}
          setContentDraft={setContentDraft}
          tree={tree}
          onNavigate={selectNode}
        />
      )}

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
        title={t('discard.title')}
        message={t('discard.message')}
        confirmLabel={t('discard.confirm')}
        cancelLabel={t('discard.cancel')}
        onConfirm={handleDiscardConfirm}
        onCancel={handleDiscardCancel}
      />

      {/* ── Delete confirmation modal ── */}
      <ConfirmModal
        open={!!deleteConfirm}
        icon="🗑️"
        title={deleteConfirm?.type === 'folder' ? t('delete.folderTitle') : t('delete.documentTitle')}
        message={deleteConfirm?.title ? t('delete.messageNamed', { title: deleteConfirm.title }) : t('delete.message')}
        confirmLabel={t('delete.confirm')}
        cancelLabel={t('delete.cancel')}
        onConfirm={handleDeleteConfirm}
        onCancel={handleDeleteCancel}
      />

      {/* ── Document load error modal ── */}
      <ErrorModal
        open={!!notFoundDocId || !!docLoadError}
        icon={notFoundDocId ? '🔍' : '⚠️'}
        title={notFoundDocId ? t('loadError.notFoundTitle') : t('loadError.errorTitle')}
        message={
          notFoundDocId
            ? t('loadError.notFoundMessage')
            : docLoadError && docLoadError.status !== 'network'
            ? t('loadError.errorMessageWithCode', { code: docLoadError.status })
            : t('loadError.errorMessage')
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
        title={t('loadError.saveErrorTitle')}
        message={saveError?.message || t('loadError.saveErrorMessage')}
        onClose={() => setSaveError(null)}
      />
    </>
  );
};

export default KnowledgeBase;
