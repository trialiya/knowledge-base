import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import FindBar from '@/components/common/search/FindBar';
import useAddressFind from '@/components/common/search/useAddressFind';
import { findSectionHeading } from '@/components/common/preview/sectionAnchor';
import DetailHeader from './DetailHeader';
import MarkdownEditor from '../editor/MarkdownEditor';

/**
 * Центр раздела «База знаний» для документа: шапка, find-бар и редактор
 * содержимого.
 *
 * AI-summary, вложения и метаданные живут в правой панели (см.
 * detailSidebar.jsx), поэтому вкладок в центре больше нет — здесь ровно то, что
 * пользователь читает и правит. Открывается редактор в режиме просмотра
 * (`defaultPreview`): документ чаще читают, чем правят. Состояние
 * черновика/полноэкранного режима поднято в KnowledgeBase (его делят центр и
 * правая панель), сюда приходит пропсами.
 *
 * `find` и `section` — что подсветить и с какого раздела начать, из адреса;
 * менять запрос обратно в адрес — дело `onFindChange`. Пусто — документ открыли
 * не из поиска: бара нет, пока его не позовут Ctrl+F. `findActive` снимает
 * шорткаты, пока раздел смонтирован под другой вкладкой.
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
  find = '',
  section = '',
  onFindChange = null,
  findActive = true,
}) => {
  const { t } = useTranslation('knowledgeBase');
  const bodyRef = useRef(null);
  const search = useAddressFind({
    rootRef: bodyRef,
    find,
    anchor: section,
    resolveAnchor: findSectionHeading,
    active: findActive,
    onCommit: onFindChange,
  });

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      {search.open && (
        <FindBar
          className="find-bar--doc"
          placeholder={t('find.placeholder')}
          inputRef={search.inputRef}
          query={search.query}
          onQueryChange={search.onQueryChange}
          onCommit={search.onCommitQuery}
          total={search.total}
          activeIndex={search.activeIndex}
          onPrev={search.goPrev}
          onNext={search.goNext}
          onClose={search.close}
        />
      )}

      <div className="detail-body" ref={bodyRef}>
        <MarkdownEditor
          value={contentDraft}
          onChange={setContentDraft}
          savedValue={node.description || ''}
          placeholder={t('detail.docPlaceholder')}
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

export default DocumentDetail;
