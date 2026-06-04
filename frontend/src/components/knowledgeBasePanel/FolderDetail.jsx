import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import DetailHeader from './DetailHeader';
import ContentsTable from './ContentsTable';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from '../common/AttachmentPanel';
import FullscreenEditorModal from './FullscreenEditorModal';
import useFolderChildren from './useFolderChildren';
import HistoryModal from './HistoryModal';

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
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null); // 'about' | 'content' | null
  const [showHistory, setShowHistory] = useState(false);
  const { children, loading: childrenLoading } = useFolderChildren(node, onLoadChildren);

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <div className="detail-tabs">
        {TABS.map(({ key, labelKey }) => (
          <button
            key={key}
            className={`detail-tab ${tab === key ? 'detail-tab--active' : ''}`}
            onClick={() => onTabChange(key)}
          >
            {t(labelKey)}
            {key === 'attachments' && attachmentCount > 0 && (
              <span className="detail-tab__count">{attachmentCount}</span>
            )}
          </button>
        ))}
      </div>

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

      {fullscreen && (
        <FullscreenEditorModal
          title={
            fullscreen === 'about'
              ? t('detail.fullscreenAbout', { title: node.title })
              : t('detail.fullscreenContent', { title: node.title })
          }
          value={node.description || ''}
          previewOnly={fullscreen === 'about'}
          onSave={(val) => onUpdate(node.id, { description: val })}
          onClose={() => setFullscreen(null)}
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
          onRestore={(val) => onUpdate(node.id, { description: val })}
          onClose={() => setShowHistory(false)}
        />
      )}
    </div>
  );
};

export default FolderDetail;
