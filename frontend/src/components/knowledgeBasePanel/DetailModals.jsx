import React from 'react';
import { useTranslation } from 'react-i18next';
import FullscreenEditorModal from './FullscreenEditorModal';
import HistoryModal from './HistoryModal';

/**
 * The fullscreen-editor + history modal tail shared verbatim by FolderDetail
 * and DocumentDetail.
 *
 * props:
 *   node              — the document/folder node
 *   fullscreen        — 'about' | 'content' | null
 *   onCloseFullscreen — () => void
 *   showHistory       — boolean
 *   onCloseHistory    — () => void
 *   onUpdate          — (id, patch) => void
 *   tree, onNavigate  — forwarded to the editors for DocLinkTooltip
 */
const DetailModals = ({
  node,
  fullscreen,
  onCloseFullscreen,
  showHistory,
  onCloseHistory,
  onUpdate,
  tree = [],
  onNavigate,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const saveDescription = (val) => onUpdate(node.id, { description: val });

  return (
    <>
      {fullscreen && (
        <FullscreenEditorModal
          title={
            fullscreen === 'about'
              ? t('detail.fullscreenAbout', { title: node.title })
              : t('detail.fullscreenContent', { title: node.title })
          }
          value={node.description || ''}
          previewOnly={fullscreen === 'about'}
          onSave={saveDescription}
          onClose={onCloseFullscreen}
          tree={tree}
          onNavigate={onNavigate}
        />
      )}
      {showHistory && (
        <HistoryModal
          documentId={node.id}
          documentTitle={node.title}
          tree={tree}
          onNavigate={onNavigate}
          onRestore={saveDescription}
          onClose={onCloseHistory}
        />
      )}
    </>
  );
};

export default DetailModals;
