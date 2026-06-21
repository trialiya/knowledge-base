import React, { useState, useEffect } from 'react';
import ReactDOM from 'react-dom';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { getToolIcon, humanizeTool, toolLabelKey } from './toolMeta';
import './styles/tool-call-detail-modal.css';

const formatJson = (raw) => {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

const ToolCallDetailModal = ({ conversationId, runId, tc, onClose }) => {
  const { t } = useTranslation('chat');
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    chatApi
      .getToolCallDetails(conversationId, runId)
      .then((data) => {
        if (cancelled) return;
        const match = data.find(
          (d) => d.name === tc.name && JSON.stringify(JSON.parse(d.argumentsRaw || 'null')) === JSON.stringify(tc.arguments || null),
        ) || data.find((d) => d.name === tc.name) || data[0] || null;
        setDetails(match);
        setLoading(false);
      })
      .catch(() => {
        if (!cancelled) {
          setError(t('toolCall.detail.loadError'));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, runId, tc.name, t]);

  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const statusClass = details ? `tcd-status--${details.status.toLowerCase()}` : '';

  return ReactDOM.createPortal(
    <div className="tcd-overlay" onClick={onClose}>
      <div className="tcd-modal" onClick={(e) => e.stopPropagation()}>
        <div className="tcd-header">
          <span className="tcd-title">
            <span className="tcd-icon" aria-hidden="true">{icon}</span>
            {label}
          </span>
          <button className="tcd-close" onClick={onClose} title={t('close')}>✕</button>
        </div>

        {loading && <div className="tcd-loading">{t('loading')}</div>}
        {error && <div className="tcd-error">{error}</div>}

        {details && !loading && (
          <div className="tcd-body">
            <div className={`tcd-status-badge ${statusClass}`}>{details.status}</div>

            <section className="tcd-section">
              <div className="tcd-section-label">{t('toolCall.detail.arguments')}</div>
              <pre className="tcd-pre">{formatJson(details.argumentsRaw) || '—'}</pre>
            </section>

            <section className="tcd-section">
              <div className="tcd-section-label">{t('toolCall.detail.result')}</div>
              <pre className="tcd-pre tcd-pre--result">{details.resultText || '—'}</pre>
            </section>

            {details.error && (
              <section className="tcd-section">
                <div className="tcd-section-label tcd-section-label--error">{t('toolCall.error')}</div>
                <pre className="tcd-pre tcd-pre--error">{details.error}</pre>
              </section>
            )}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
};

export default ToolCallDetailModal;
