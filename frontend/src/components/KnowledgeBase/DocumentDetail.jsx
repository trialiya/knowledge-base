import React from 'react';
import DetailHeader from './DetailHeader';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
];

const DocumentDetail = ({ node, path, tab, onTabChange, onUpdate, onDelete, onNavigate, onRename }) => (
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
        </button>
      ))}
    </div>

    <div className="detail-body">
      {tab === 'summary' && (
        <div className="summary-tab">
          <SummarySection label="About" description={node.description} onEdit={() => onTabChange('content')} />
        </div>
      )}

      {tab === 'content' && (
        <MarkdownEditor
          value={node.description || ''}
          placeholder="# Markdown контент..."
          onSave={(val) => onUpdate(node.id, { description: val })}
        />
      )}
    </div>
  </div>
);

export default DocumentDetail;
