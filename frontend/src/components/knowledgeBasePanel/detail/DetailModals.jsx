import { useTranslation } from 'react-i18next';
import FullscreenEditorModal from '../editor/FullscreenEditorModal';
import HistoryModal from '../modals/HistoryModal';

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
 *   guard             — (action) => void; спрашивает про несохранённые правки
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
  guard,
  tree = [],
  onNavigate,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const saveDescription = (val) => onUpdate(node.id, { description: val });
  // Откат — явный выбор текста: он сохраняется и становится черновиком. Правки,
  // набранные поверх прежней версии, иначе остались бы в редакторе, и их
  // «Сохранить» молча перезаписало бы откат, — поэтому сначала вопрос о них.
  const restoreDescription = (val) =>
    guard(() => {
      saveDescription(val);
      setContentDraft(val);
    });

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
          onRestore={restoreDescription}
          onClose={onCloseHistory}
        />
      )}
    </>
  );
};

export default DetailModals;
