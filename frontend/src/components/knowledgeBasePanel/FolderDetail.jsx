import React, { useState } from 'react';
import DetailHeader from './DetailHeader';
import ContentsTable from './ContentsTable';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from '../common/AttachmentPanel';
import FullscreenEditorModal from './FullscreenEditorModal';
import useFolderChildren from './useFolderChildren';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
  { key: 'contents', label: 'Contents' },
  { key: 'attachments', label: 'Attachments' },
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
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null); // 'about' | 'content' | null
  const { children, loading: childrenLoading } = useFolderChildren(node, onLoadChildren);

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={onRename} onDelete={onDelete} />

      <div className="detail-tabs">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            className={`detail-tab ${tab === key ? 'detail-tab--active' : ''}`}
            onClick={() => onTabChange(key)}
          >
            {label}
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
              label="About"
              description={node.description}
              onEdit={() => onTabChange('content')}
              onExpand={() => setFullscreen('about')}
              tree={tree}
              onNavigate={onNavigate}
            />
            {children.length > 0 && (
              <SummarySection label="Contents" showMoreBtn onMore={() => onTabChange('contents')}>
                <ContentsTable children={children} onNavigate={onNavigate} />
              </SummarySection>
            )}
            <SummarySection label="Attachments" showMoreBtn onMore={() => onTabChange('attachments')}>
              <AttachmentPanel ownerType="document" ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder="Описание папки..."
            onSave={(val) => onUpdate(node.id, { description: val })}
            onExpand={() => setFullscreen('content')}
            tree={tree}
            onNavigate={onNavigate}
          />
        )}

        {tab === 'contents' && (
          <div className="contents-tab">
            {childrenLoading && children.length === 0 ? (
              <p className="empty-tab">Загрузка…</p>
            ) : children.length === 0 ? (
              <p className="empty-tab">Папка пуста</p>
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
          title={fullscreen === 'about' ? `${node.title} — About` : `${node.title} — Content`}
          value={node.description || ''}
          previewOnly={fullscreen === 'about'}
          onSave={(val) => onUpdate(node.id, { description: val })}
          onClose={() => setFullscreen(null)}
          tree={tree}
          onNavigate={onNavigate}
        />
      )}
    </div>
  );
};

export default FolderDetail;
