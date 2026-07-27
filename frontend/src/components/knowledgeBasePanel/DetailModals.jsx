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
 *   fullscreen        — раскрыт ли редактор содержимого на весь экран
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

  return (
    <>
      {fullscreen && (
        <FullscreenEditorModal
          title={t('detail.fullscreenContent', { title: node.title })}
          // Значение — общий черновик, поэтому развёрнутое окно открывается
          // с текущими несохранёнными правками встроенного редактора.
          value={contentDraft}
          onChange={setContentDraft}
          savedValue={node.description || ''}
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
