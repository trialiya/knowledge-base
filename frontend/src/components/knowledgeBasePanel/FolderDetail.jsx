import { useTranslation } from 'react-i18next';
import DetailHeader from './DetailHeader';
import MarkdownEditor from './MarkdownEditor';

/**
 * Центр раздела «База знаний» для папки: шапка и редактор её описания.
 *
 * Состав папки, вложения и метаданные — в правой панели (detailSidebar.jsx);
 * список детей грузит KnowledgeBase (useFolderChildren), потому что он нужен и
 * правой панели, а не только этому компоненту. Редактор, как и у документа,
 * открывается в режиме просмотра (`defaultPreview`).
 */
const FolderDetail = ({
  node,
  path,
  onUpdate,
  onDelete,
  onNavigate,
  onRename,
  tree = [],
  contentDraft,
  setContentDraft,
  onExpandContent,
  onHistory,
}) => {
  const { t } = useTranslation('knowledgeBase');

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <div className="detail-body">
        <MarkdownEditor
          value={contentDraft}
          onChange={setContentDraft}
          savedValue={node.description || ''}
          placeholder={t('detail.folderPlaceholder')}
          onSave={(val) => onUpdate(node.id, { description: val })}
          defaultPreview
          onExpand={onExpandContent}
          tree={tree}
          onNavigate={onNavigate}
          onHistory={onHistory}
        />
      </div>
    </div>
  );
};

export default FolderDetail;
