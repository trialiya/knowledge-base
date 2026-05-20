import React, { useState } from 'react';
import DetailHeader from './DetailHeader';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from './AttachmentPanel';
import { IconPaperclip } from './AttachmentPanel';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
  { key: 'attachments', label: 'Attachments' },
];

const DocumentDetail = ({ node, path, tab, onTabChange, onUpdate, onDelete, onNavigate, onRename }) => {
  const [attachmentCount, setAttachmentCount] = useState(0);

  const handleRename = (id, name) => {
    if (onRename) onRename(id, name);
    if (onUpdate) onUpdate(id, { title: name });
  };

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={handleRename} onDelete={onDelete} />

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
            <SummarySection label="About" description={node.description} onEdit={() => onTabChange('content')} />

            {/* Attachments summary */}
            <SummarySection label="Attachments" showMoreBtn onMore={() => onTabChange('attachments')}>
              <AttachmentPanel ownerType="document" ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder="# Markdown контент..."
            onSave={(val) => onUpdate(node.id, { description: val })}
          />
        )}

        {tab === 'attachments' && (
          <AttachmentPanel ownerType="document" ownerId={node.id} onCountChange={setAttachmentCount} />
        )}
      </div>
    </div>
  );
};

export default DocumentDetail;
