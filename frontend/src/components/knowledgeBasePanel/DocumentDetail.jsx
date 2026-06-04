import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import DetailHeader from './DetailHeader';
import SummarySection from './SummarySection';
import MarkdownEditor from './MarkdownEditor';
import AttachmentPanel from '../common/AttachmentPanel';
import FullscreenEditorModal from './FullscreenEditorModal';
import { IconSparkle, IconSparkleLoading } from './icons';
import HistoryModal from './HistoryModal';

const TABS = [
  { key: 'summary', labelKey: 'tabs.summary' },
  { key: 'content', labelKey: 'tabs.content' },
  { key: 'attachments', labelKey: 'tabs.attachments' },
];

// ─── AI Summary block ─────────────────────────────────────────────────────────

const AISummarySection = ({ node, onSummarize }) => {
  const { t } = useTranslation('knowledgeBase');
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
      {t('summary.aiSummary')}
      {node.summaryStale && !summarizing && (
        <span className="summary-stale-badge" title={t('summary.staleHint')}>
          {t('summary.stale')}
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
            title={node.summaryStale ? t('summary.updateTitle') : t('summary.regenerateTitle')}
          >
            {summarizing ? <IconSparkleLoading size={11} /> : <IconSparkle size={11} />}
            {summarizing ? t('summary.generating') : node.summaryStale ? t('summary.update') : t('summary.regenerate')}
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
          title={!node.description ? t('summary.generateDisabledHint') : undefined}
        >
          {summarizing ? <IconSparkleLoading size={13} /> : <IconSparkle size={13} />}
          {summarizing ? t('summary.generating') : t('summary.generate')}
        </button>
        {!node.description && <p className="ai-summary-empty__hint">{t('summary.needDescription')}</p>}
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
  const { t } = useTranslation('knowledgeBase');
  const [attachmentCount, setAttachmentCount] = useState(0);
  const [fullscreen, setFullscreen] = useState(null); // 'about' | 'content' | null
  const [showHistory, setShowHistory] = useState(false);

  const handleRename = (id, name) => {
    if (onRename) onRename(id, name);
    if (onUpdate) onUpdate(id, { title: name });
  };

  return (
    <div className="detail-panel">
      <DetailHeader node={node} path={path} onNavigate={onNavigate} onRename={handleRename} onDelete={onDelete} />

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
            <AISummarySection node={node} onSummarize={onSummarize} />

            <SummarySection
              label={t('detail.about')}
              description={node.description}
              onEdit={() => onTabChange('content')}
              onExpand={() => setFullscreen('about')}
              tree={tree}
              onNavigate={onNavigate}
              copyable
            />

            <SummarySection label={t('detail.attachments')} showMoreBtn onMore={() => onTabChange('attachments')}>
              <AttachmentPanel ownerType="document" ownerId={node.id} compact onCountChange={setAttachmentCount} />
            </SummarySection>
          </div>
        )}

        {tab === 'content' && (
          <MarkdownEditor
            value={node.description || ''}
            placeholder={t('detail.docPlaceholder')}
            onSave={(val) => onUpdate(node.id, { description: val })}
            onExpand={() => setFullscreen('content')}
            tree={tree}
            onNavigate={onNavigate}
            onHistory={() => setShowHistory(true)}
          />
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

export default DocumentDetail;
