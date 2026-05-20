import React from 'react';
import DetailHeader from './DetailHeader';
import ContentsTable from './ContentsTable';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
  { key: 'contents', label: 'Contents' },
];

const FolderDetail = ({ node, path, tab, onTabChange, onUpdate, onDelete, onNavigate, onRename }) => {
  const children = node.children || [];

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
          </button>
        ))}
      </div>

      <div className="detail-body">
        {tab === 'summary' && (
          <div className="summary-tab">
            <SummarySection label="About" description={node.description} onEdit={() => onTabChange('content')} />
            {children.length > 0 && (
              <SummarySection label="Contents" showMoreBtn onMore={() => onTabChange('contents')}>
                <ContentsTable children={children} onNavigate={onNavigate} />
              </SummarySection>
            )}
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder="Описание папки..."
            onSave={(val) => onUpdate(node.id, { description: val })}
          />
        )}

        {tab === 'contents' && (
          <div className="contents-tab">
            {children.length === 0 ? (
              <p className="empty-tab">Папка пуста</p>
            ) : (
              <ContentsTable children={children} onNavigate={onNavigate} />
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default FolderDetail;
