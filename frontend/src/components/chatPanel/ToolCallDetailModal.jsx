import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { getToolIcon, humanizeTool, toolLabelKey } from '../common/toolNames';
import { IconCopySmall, IconCopied } from '../../icons';
import useCopyFeedback from '../../hooks/useCopyFeedback';
import ModalShell from '../common/ModalShell';
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
  const [copied, copy] = useCopyFeedback();

  if (!value) return null;

  return (
    <button
      type="button"
      className={`tcd-copy-btn ${copied ? 'tcd-copy-btn--done' : ''}`}
      onClick={() => copy(value)}
      title={copied ? t('common:copied') : t('toolCall.copy')}
    >
      {copied ? <IconCopied /> : <IconCopySmall />}
    </button>
  );
};

const ToolCallDetailModal = ({ conversationId, callId, tc, onClose }) => {
  const { t } = useTranslation('chat');
  // Ответ сервера; null — запрос ещё идёт. `loading`/`error` выводятся из него
  // при рендере, а сброс на смену вызова делается тут же — эффект показал бы
  // кадр с деталями предыдущего инструмента.
  const [answer, setAnswer] = useState(null); // { details, failed } | null

  const [req, setReq] = useState({ conversationId, callId });
  if (req.conversationId !== conversationId || req.callId !== callId) {
    setReq({ conversationId, callId });
    setAnswer(null);
  }

  useEffect(() => {
    if (!callId) return undefined;
    let cancelled = false;
    chatApi
      .getToolCallDetails(conversationId, callId)
      .then((data) => {
        if (!cancelled) setAnswer({ details: data || null, failed: false });
      })
      .catch(() => {
        if (!cancelled) setAnswer({ details: null, failed: true });
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, callId]);

  // Без callId запроса нет вовсе — сразу ошибка.
  const loading = !!callId && answer === null;
  const details = answer?.details ?? null;
  const error = !callId || answer?.failed ? t('toolCall.detail.loadError') : null;

  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const statusClass = details ? `tcd-status--${details.status.toLowerCase()}` : '';
  const argsPretty = details ? formatJson(details.argumentsRaw) : null;
  const resultPretty = details ? tryFormatJson(details.resultText) : null;

  return (
    <ModalShell onClose={onClose} className="tcd-modal">
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
    </ModalShell>
  );
};

export default ToolCallDetailModal;
