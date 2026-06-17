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
 *   contentDraft      — «поднятый» черновик описания (общий с встроенным редактором)
 *   setContentDraft   — (val) => void
 *   tree, onNavigate  — forwarded to the editors for DocLinkTooltip
 */
const DetailModals = ({
  node,
  fullscreen,
  onCloseFullscreen,
  showHistory,
  onCloseHistory,
  onUpdate,
  contentDraft = '',
  setContentDraft,
  tree = [],
  onNavigate,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const saveDescription = (val) => onUpdate(node.id, { description: val });
  const isAbout = fullscreen === 'about';

  return (
    <>
      {fullscreen && (
        <FullscreenEditorModal
          title={
            isAbout
              ? t('detail.fullscreenAbout', { title: node.title })
              : t('detail.fullscreenContent', { title: node.title })
          }
          // About — это превью сохранённого описания; Content — общий черновик,
          // поэтому развёрнутое окно открывается с текущими несохранёнными правками.
          value={isAbout ? node.description || '' : contentDraft}
          onChange={isAbout ? undefined : setContentDraft}
          savedValue={node.description || ''}
          previewOnly={isAbout}
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
