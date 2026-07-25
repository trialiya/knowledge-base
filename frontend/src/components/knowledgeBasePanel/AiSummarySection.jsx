import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconSparkle, IconSparkleLoading } from '../../icons';

/**
 * Блок AI-описания документа: показывает готовое описание с кнопкой
 * перегенерации, либо предложение его создать. Жил внутри DocumentDetail —
 * вынесен отдельно, когда описание переехало из вкладки центра в правую панель
 * (один файл = один экспортируемый компонент).
 *
 * props:
 *   node        — узел базы знаний (нужны summary / summaryStale / description)
 *   onSummarize — (id) => Promise, запуск суммаризации
 */
const AiSummarySection = ({ node, onSummarize }) => {
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
            className={`btn btn--sm btn--ghost ai-summary-regenerate${
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
          className="btn btn--primary"
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

export default AiSummarySection;
