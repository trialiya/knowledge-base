import React, { useState } from 'react';
import DetailHeader from './DetailHeader';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from './AttachmentPanel';
import FullscreenEditorModal from './FullscreenEditorModal';
import { IconSparkle, IconSparkleLoading } from './icons';

const TABS = [
  { key: 'summary', label: 'Summary' },
  { key: 'content', label: 'Content' },
  { key: 'attachments', label: 'Attachments' },
];

// ─── AI Summary block ─────────────────────────────────────────────────────────

const AISummarySection = ({ node, onSummarize }) => {
  const [summarizing, setSummarizing] = useState(false);

  const handleSummarize = async () => {
    if (!onSummarize || summarizing) return;
    setSummarizing(true);
    try {
      await onSummarize(node.id);
    } finally {
      setSummarizing(false);
    }
  };

  const label = (
    <span className="summary-section__label-ai">
      {summarizing ? <IconSparkleLoading size={12} /> : <IconSparkle size={12} />}
      AI Summary
      {node.summaryStale && !summarizing && (
        <span className="summary-stale-badge" title="Описание изменилось — summary может быть устаревшим">
          устарел
        </span>
      )}
    </span>
  );

  if (node.summary) {
    return (
      <section className="summary-section ai-summary-section">
        <div className="summary-section__head">
          <span className="summary-section__label">{label}</span>
          <button
            className={`ai-summary-regenerate${summarizing ? ' ai-summary-regenerate--loading' : ''}${
              node.summaryStale ? ' ai-summary-regenerate--stale' : ''
            }`}
            onClick={handleSummarize}
            disabled={summarizing}
            title={node.summaryStale ? 'Обновить устаревший summary' : 'Перегенерировать summary'}
          >
            {summarizing ? <IconSparkleLoading size={11} /> : <IconSparkle size={11} />}
            {summarizing ? 'Генерация…' : node.summaryStale ? 'Обновить' : 'Перегенерировать'}
          </button>
        </div>
        <div className="summary-about">
          <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{node.summary}</p>
        </div>
      </section>
    );
  }

  return (
    <section className="summary-section ai-summary-section">
      <div className="summary-section__head">
        <span className="summary-section__label">{label}</span>
      </div>
      <div className="ai-summary-empty">
        <button
          className={`ai-summary-generate-btn${summarizing ? ' ai-summary-generate-btn--loading' : ''}`}
          onClick={handleSummarize}
          disabled={summarizing || !node.description}
          title={!node.description ? 'Добавьте описание документа, чтобы сгенерировать summary' : undefined}
        >
          {summarizing ? <IconSparkleLoading size={13} /> : <IconSparkle size={13} />}
          {summarizing ? 'Генерация…' : 'Сгенерировать summary'}
        </button>
        {!node.description && <p className="ai-summary-empty__hint">Сначала добавьте описание во вкладке Content</p>}
      </div>
    </section>
  );
};

// ─── DocumentDetail ───────────────────────────────────────────────────────────

const DocumentDetail = ({
  node,
  path,
  tab,
  onTabChange,
  onUpdate,
  onDelete,
  onNavigate,
  onRename,
  onSummarize,
  tree = [],
}) => {
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null); // 'about' | 'content' | null

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
            <AISummarySection node={node} onSummarize={onSummarize} />

            <SummarySection
              label="About"
              description={node.description}
              onEdit={() => onTabChange('content')}
              onExpand={() => setFullscreen('about')}
              tree={tree}
              onNavigate={onNavigate}
            />

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
            onExpand={() => setFullscreen('content')}
            tree={tree}
            onNavigate={onNavigate}
          />
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

export default DocumentDetail;
