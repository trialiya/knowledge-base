import React from 'react';
import { useTranslation } from 'react-i18next';
import DetailHeader from './DetailHeader';
import MarkdownEditor from './MarkdownEditor';

/**
 * Центр раздела «База знаний» для документа: шапка и редактор содержимого.
 *
 * Описание, вложения и метаданные переехали в правую панель (см.
 * detailSidebar.jsx), поэтому вкладок в центре больше нет — здесь ровно то, что
 * пользователь редактирует. Состояние черновика/полноэкранного режима поднято в
 * KnowledgeBase (его делят центр и правая панель), сюда приходит пропсами.
 */
const DocumentDetail = ({
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

  const handleRename = (id, name) => {
    if (onRename) onRename(id, name);
    if (onUpdate) onUpdate(id, { title: name });
  };

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={handleRename} onDelete={onDelete} />

      <div className="detail-body">
        <MarkdownEditor
          value={contentDraft}
          onChange={setContentDraft}
          savedValue={node.description || ''}
          placeholder={t('detail.docPlaceholder')}
          onSave={(val) => onUpdate(node.id, { description: val })}
          onExpand={onExpandContent}
          tree={tree}
          onNavigate={onNavigate}
          onHistory={onHistory}
        />
      </div>
    </div>
  );
};

export default DocumentDetail;
