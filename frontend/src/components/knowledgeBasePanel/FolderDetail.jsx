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

const TABS = [
  { key: 'summary', labelKey: 'tabs.summary' },
  { key: 'content', labelKey: 'tabs.content' },
  { key: 'contents', labelKey: 'tabs.contents' },
  { key: 'attachments', labelKey: 'tabs.attachments' },
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
  const { attachmentCount, setAttachmentCount, fullscreen, setFullscreen, showHistory, setShowHistory } =
    useDetailPanel();
  const { children, loading: childrenLoading } = useFolderChildren(node, onLoadChildren);

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <DetailTabs tabs={TABS} tab={tab} onTabChange={onTabChange} attachmentCount={attachmentCount} />

      <div className="detail-body">
        {tab === 'summary' && (
          <div className="summary-tab">
            <SummarySection
              label={t('detail.about')}
              description={node.description}
              onEdit={() => onTabChange('content')}
              onExpand={() => setFullscreen('about')}
              tree={tree}
              onNavigate={onNavigate}
              copyable
            />
            {children.length > 0 && (
              <SummarySection label={t('detail.contents')} showMoreBtn onMore={() => onTabChange('contents')}>
                <ContentsTable children={children} onNavigate={onNavigate} />
              </SummarySection>
            )}
            <SummarySection label={t('detail.attachments')} showMoreBtn onMore={() => onTabChange('attachments')}>
              <AttachmentPanel ownerType="document" ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder={t('detail.folderPlaceholder')}
            onSave={(val) => onUpdate(node.id, { description: val })}
            onExpand={() => setFullscreen('content')}
            tree={tree}
            onNavigate={onNavigate}
            onHistory={() => setShowHistory(true)}
          />
        )}

        {tab === 'contents' && (
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

        {tab === 'attachments' && (
          <AttachmentPanel ownerType="document" ownerId={node.id} onCountChange={setAttachmentCount} />
        )}
      </div>

      <DetailModals
        node={node}
        fullscreen={fullscreen}
        onCloseFullscreen={() => setFullscreen(null)}
        showHistory={showHistory}
        onCloseHistory={() => setShowHistory(false)}
        onUpdate={onUpdate}
        tree={tree}
        onNavigate={onNavigate}
      />
    </div>
  );
};

export default FolderDetail;
