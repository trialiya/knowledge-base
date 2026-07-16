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

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const highlightJson = (text) => {
  const escaped = escapeHtml(text);
  return escaped.replace(
    /("(?:\\u[0-9a-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (match) => {
      let cls;
      if (match.startsWith('"')) {
        cls = match.endsWith(':') ? 'json-key' : 'json-string';
      } else if (match === 'true' || match === 'false') {
        cls = 'json-boolean';
      } else if (match === 'null') {
        cls = 'json-null';
      } else {
        cls = 'json-number';
      }
      return `<span class="${cls}">${match}</span>`;
    },
  );
};

const tryFormatJson = (raw) => {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return null;
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

const ToolCallDetailModal = ({ conversationId, runId, callIndex, tc, onClose }) => {
  const { t } = useTranslation('chat');
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!runId || callIndex == null || callIndex < 0) {
      setError(t('toolCall.detail.loadError'));
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    chatApi
      .getToolCallDetails(conversationId, runId, callIndex)
      .then((data) => {
        if (cancelled) return;
        setDetails(data || null);
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
  }, [conversationId, runId, callIndex, t]);

  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const statusClass = details ? `tcd-status--${details.status.toLowerCase()}` : '';
  const argsPretty = details ? formatJson(details.argumentsRaw) : null;
  const resultPretty = details ? tryFormatJson(details.resultText) : null;

  return ReactDOM.createPortal(
    <div className="tcd-overlay" onClick={onClose}>
      <div className="tcd-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
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
        {!loading && !error && !details && <div className="tcd-error">{t('toolCall.detail.notFound')}</div>}

        {details && !loading && (
          <div className="tcd-body">
            <div className={`tcd-status-badge ${statusClass}`}>{details.status}</div>

            <section className="tcd-section">
              <div className="tcd-section-header">
                <div className="tcd-section-label">{t('toolCall.detail.arguments')}</div>
                <CopyButton value={argsPretty} />
              </div>
              {argsPretty ? (
                <pre className="tcd-pre" dangerouslySetInnerHTML={{ __html: highlightJson(argsPretty) }} />
              ) : (
                <pre className="tcd-pre">—</pre>
              )}
            </section>

            <section className="tcd-section">
              <div className="tcd-section-header">
                <div className="tcd-section-label">{t('toolCall.detail.result')}</div>
                <CopyButton value={details.resultText} />
              </div>
              {resultPretty ? (
                <pre
                  className="tcd-pre tcd-pre--result"
                  dangerouslySetInnerHTML={{ __html: highlightJson(resultPretty) }}
                />
              ) : (
                <pre className="tcd-pre tcd-pre--result">{details.resultText || '—'}</pre>
              )}
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
