import React, { useState, useEffect, useRef } from 'react';
import ReactDOM from 'react-dom';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { getToolIcon, humanizeTool, toolLabelKey } from './toolMeta';
import { IconCopySmall, IconCopied } from '../../icons';
import { COPY_DONE_MS } from '../../constants/ui';
import './styles/tool-call-detail-modal.css';

const formatJson = (raw) => {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

/** Маленькая кнопка копирования содержимого секции (аргументы/результат). */
const CopyButton = ({ value }) => {
  const { t } = useTranslation('chat');
  const [copied, setCopied] = useState(false);
  const timerRef = useRef(null);

  useEffect(() => () => clearTimeout(timerRef.current), []);

  if (!value) return null;

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => setCopied(false), COPY_DONE_MS);
    } catch {
      /* clipboard API may fail in insecure contexts */
    }
  };

  return (
    <button
      type="button"
      className={`tcd-copy-btn ${copied ? 'tcd-copy-btn--done' : ''}`}
      onClick={handleCopy}
      title={copied ? t('common:copied') : t('toolCall.copy')}
    >
      {copied ? <IconCopied /> : <IconCopySmall />}
    </button>
  );
};

const ToolCallDetailModal = ({ conversationId, runId, tc, onClose }) => {
  const { t } = useTranslation('chat');
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!runId) {
      setError(t('toolCall.detail.loadError'));
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    chatApi
      .getToolCallDetails(conversationId, runId)
      .then((data) => {
        if (cancelled) return;
        const match =
          data.find(
            (d) =>
              d.name === tc.name &&
              JSON.stringify(JSON.parse(d.argumentsRaw || 'null')) === JSON.stringify(tc.arguments || null),
          ) ||
          data.find((d) => d.name === tc.name) ||
          data[0] ||
          null;
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
  const argsPretty = details ? formatJson(details.argumentsRaw) : null;

  return ReactDOM.createPortal(
    <div className="tcd-overlay" onClick={onClose}>
      <div className="tcd-modal" onClick={(e) => e.stopPropagation()}>
        <div className="tcd-header">
          <span className="tcd-title">
            <span className="tcd-icon" aria-hidden="true">
              {icon}
            </span>
            {label}
          </span>
          <button className="tcd-close" onClick={onClose} title={t('close')}>
            ✕
          </button>
        </div>

        {loading && <div className="tcd-loading">{t('loading')}</div>}
        {error && <div className="tcd-error">{error}</div>}

        {details && !loading && (
          <div className="tcd-body">
            <div className={`tcd-status-badge ${statusClass}`}>{details.status}</div>

            <section className="tcd-section">
              <div className="tcd-section-header">
                <div className="tcd-section-label">{t('toolCall.detail.arguments')}</div>
                <CopyButton value={argsPretty} />
              </div>
              <pre className="tcd-pre">{argsPretty || '—'}</pre>
            </section>

            <section className="tcd-section">
              <div className="tcd-section-header">
                <div className="tcd-section-label">{t('toolCall.detail.result')}</div>
                <CopyButton value={details.resultText} />
              </div>
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
