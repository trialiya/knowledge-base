import React from 'react';
import { useTranslation } from 'react-i18next';
import DetailHeader from './DetailHeader';
import ContentsTable from './ContentsTable';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from '../common/AttachmentPanel';
import DetailTabs from './DetailTabs';
import DetailModals from './DetailModals';
import useFolderChildren from './useFolderChildren';
import useDetailPanel from './useDetailPanel';
import { DOC_TAB } from '../../constants/docTabs';
import { OWNER_TYPE } from '../../constants/ownerType';

const TABS = [
  { key: DOC_TAB.SUMMARY, labelKey: 'tabs.summary' },
  { key: DOC_TAB.CONTENT, labelKey: 'tabs.content' },
  { key: DOC_TAB.CONTENTS, labelKey: 'tabs.contents' },
  { key: DOC_TAB.ATTACHMENTS, labelKey: 'tabs.attachments' },
];

const FolderDetail = ({
  node,
  path,
  tab,
  onTabChange,
  onUpdate,
  onDelete,
  onNavigate,
  onRename,
  onLoadChildren,
  tree = [],
}) => {
  const { t } = useTranslation('knowledgeBase');
  const {
    attachmentCount,
    setAttachmentCount,
    fullscreen,
    setFullscreen,
    showHistory,
    setShowHistory,
    contentDraft,
    setContentDraft,
  } = useDetailPanel(node.description || '');
  const { children, loading: childrenLoading } = useFolderChildren(node, onLoadChildren);

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <DetailTabs tabs={TABS} tab={tab} onTabChange={onTabChange} attachmentCount={attachmentCount} />

      <div className="detail-body">
        {tab === DOC_TAB.SUMMARY && (
          <div className="summary-tab">
            <SummarySection
              label={t('detail.about')}
              description={node.description}
              onEdit={() => onTabChange(DOC_TAB.CONTENT)}
              onExpand={() => setFullscreen('about')}
              tree={tree}
              onNavigate={onNavigate}
              copyable
            />
            {children.length > 0 && (
              <SummarySection label={t('detail.contents')} showMoreBtn onMore={() => onTabChange(DOC_TAB.CONTENTS)}>
                <ContentsTable children={children} onNavigate={onNavigate} />
              </SummarySection>
            )}
            <SummarySection label={t('detail.attachments')} showMoreBtn onMore={() => onTabChange(DOC_TAB.ATTACHMENTS)}>
              <AttachmentPanel ownerType={OWNER_TYPE.DOCUMENT} ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === DOC_TAB.CONTENT && (
          <MarkdownEditor
            value={contentDraft}
            onChange={setContentDraft}
            savedValue={node.description || ''}
            placeholder={t('detail.folderPlaceholder')}
            onSave={(val) => onUpdate(node.id, { description: val })}
            onExpand={() => setFullscreen('content')}
            tree={tree}
            onNavigate={onNavigate}
            onHistory={() => setShowHistory(true)}
          />
        )}

        {tab === DOC_TAB.CONTENTS && (
          <div className="contents-tab">
            {childrenLoading && children.length === 0 ? (
              <p className="empty-tab">{t('detail.loading')}</p>
            ) : children.length === 0 ? (
              <p className="empty-tab">{t('detail.folderEmpty')}</p>
            ) : (
              <ContentsTable children={children} onNavigate={onNavigate} />
            )}
          </div>
        )}

        {tab === DOC_TAB.ATTACHMENTS && (
          <AttachmentPanel ownerType={OWNER_TYPE.DOCUMENT} ownerId={node.id} onCountChange={setAttachmentCount} />
        )}
      </div>

      <DetailModals
        node={node}
        fullscreen={fullscreen}
        onCloseFullscreen={() => setFullscreen(null)}
        showHistory={showHistory}
        onCloseHistory={() => setShowHistory(false)}
        onUpdate={onUpdate}
        contentDraft={contentDraft}
        setContentDraft={setContentDraft}
        tree={tree}
        onNavigate={onNavigate}
      />
    </div>
  );
};

export default FolderDetail;
