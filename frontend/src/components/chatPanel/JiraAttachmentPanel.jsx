import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import './jiraAttachmentPanel.css';

// ─── Icons ────────────────────────────────────────────────────────────────────

const IconJira = () => (
  <svg width="16" height="16" viewBox="0 0 32 32" fill="none">
    <path d="M16 2L2 16l14 14 14-14L16 2zm0 4.83L25.17 16 16 25.17 6.83 16 16 6.83z" fill="currentColor" opacity=".4" />
    <path d="M16 9.66L9.66 16 16 22.34 22.34 16 16 9.66z" fill="currentColor" />
  </svg>
);

const IconConfluence = () => (
  <svg width="16" height="16" viewBox="0 0 32 32" fill="none">
    <path
      d="M2.5 22.6c-.4.6-.8 1.4-.4 2 .2.3.6.5 1 .5h7.2c.5 0 .9-.3 1.1-.7 1-1.9 1.9-3.5 5.6-3.5s4.6 1.6 5.6 3.5c.2.4.6.7 1.1.7H30c.4 0 .8-.2 1-.5.4-.6 0-1.4-.4-2C27.4 18 22 14.7 16 14.7S4.6 18 2.5 22.6z"
      fill="currentColor"
    />
    <path
      d="M29.5 9.4c.4-.6.8-1.4.4-2-.2-.3-.6-.5-1-.5h-7.2c-.5 0-.9.3-1.1.7C19.6 9.5 18.7 11 15 11s-4.6-1.5-5.6-3.4C9.2 7.2 8.8 7 8.3 7H1.1c-.4 0-.8.2-1 .5-.4.6 0 1.4.4 2C4.6 14 10 17.3 16 17.3s11.4-3.3 13.5-7.9z"
      fill="currentColor"
      opacity=".6"
    />
  </svg>
);

const IconFile = () => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>
);

const IconRefresh = ({ spinning }) => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2.2"
    strokeLinecap="round"
    strokeLinejoin="round"
    style={{ animation: spinning ? 'jira-panel-spin 0.8s linear infinite' : 'none' }}
  >
    <polyline points="23 4 23 10 17 10" />
    <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
  </svg>
);

const IconEye = () => (
  <svg
    width="13"
    height="13"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const IconClose = () => (
  <svg
    width="12"
    height="12"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2.5"
    strokeLinecap="round"
  >
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const IconLink = () => (
  <svg
    width="11"
    height="11"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
    <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
  </svg>
);

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function detectAttachmentType(attachment) {
  if (attachment.sourceUrl?.includes('atlassian.net/browse') || attachment.fileName?.match(/^[A-Z]+-\d+\.md$/)) {
    return 'jira';
  }
  if (attachment.sourceUrl?.includes('/wiki/') || attachment.fileName?.startsWith('confluence-')) {
    return 'confluence';
  }
  return 'file';
}

// ─── Content viewer ───────────────────────────────────────────────────────────

const ContentDrawer = ({ attachment, onClose }) => {
  const { t } = useTranslation('chat');
  const [content, setContent] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/attachments/${attachment.id}/content`)
      .then((r) => r.text())
      .then((text) => {
        if (!cancelled) {
          setContent(text);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setContent(t('common:loadError'));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [attachment.id, t]);

  return (
    <div className="jira-drawer-overlay" onClick={onClose}>
      <div className="jira-drawer" onClick={(e) => e.stopPropagation()}>
        <div className="jira-drawer__header">
          <span className="jira-drawer__title">{attachment.fileName}</span>
          <button className="jira-drawer__close" onClick={onClose}>
            <IconClose />
          </button>
        </div>
        <div className="jira-drawer__body">
          {loading ? (
            <p className="jira-drawer__loading">{t('common:loading')}</p>
          ) : (
            <pre className="jira-drawer__content">{content}</pre>
          )}
        </div>
      </div>
    </div>
  );
};

// ─── Single attachment card ───────────────────────────────────────────────────

const AttachmentCard = ({ attachment, onView }) => {
  const { t } = useTranslation('chat');
  const type = detectAttachmentType(attachment);

  return (
    <div className={`jira-att-card jira-att-card--${type}`}>
      <div className="jira-att-card__head">
        <span className="jira-att-card__type-icon">
          {type === 'jira' ? <IconJira /> : type === 'confluence' ? <IconConfluence /> : <IconFile />}
        </span>
        <span className="jira-att-card__name" title={attachment.fileName}>
          {attachment.fileName}
        </span>
        <span className="jira-att-card__size">{formatSize(attachment.fileSize)}</span>
        <button className="jira-att-card__view-btn" onClick={() => onView(attachment)} title={t('jira.viewContent')}>
          <IconEye />
        </button>
      </div>

      {attachment.summary && <p className="jira-att-card__summary">{attachment.summary}</p>}

      {attachment.sourceUrl && (
        <a
          className="jira-att-card__source"
          href={attachment.sourceUrl}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => e.stopPropagation()}
        >
          <IconLink /> {t('jira.openSource')}
        </a>
      )}
    </div>
  );
};

// ─── Main component ───────────────────────────────────────────────────────────

/**
 * Compact attachment panel tailored for JIRA chats.
 *
 * Props:
 *   conversationId  — active chat id
 *   jiraUrl         — original JIRA issue URL (used for the Refresh button)
 *   onCountChange   — optional (count: number) => void
 */
const JiraAttachmentPanel = ({ conversationId, jiraUrl, onCountChange }) => {
  const { t } = useTranslation('chat');
  const [attachments, setAttachments] = useState([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState('');
  const [viewing, setViewing] = useState(null);

  const load = useCallback(async () => {
    if (!conversationId) return;
    setLoading(true);
    try {
      const res = await fetch(`/api/chat/${conversationId}/attachments`);
      const data = await res.json();
      const list = Array.isArray(data) ? data : [];
      setAttachments(list);
      onCountChange?.(list.length);
    } catch {
      setAttachments([]);
      onCountChange?.(0);
    } finally {
      setLoading(false);
    }
  }, [conversationId, onCountChange]);

  useEffect(() => {
    load();
  }, [load]);

  const handleRefresh = async () => {
    if (!jiraUrl) return;
    setRefreshing(true);
    setRefreshError('');
    try {
      const res = await fetch(`/api/chats/${encodeURIComponent(conversationId)}/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: jiraUrl,
      });
      if (!res.ok) throw new Error(await res.text());
      await load();
    } catch (e) {
      setRefreshError(e.message || t('jira.refreshError'));
    } finally {
      setRefreshing(false);
    }
  };

  const jiraAttachment = attachments.find((a) => detectAttachmentType(a) === 'jira');
  const others = attachments.filter((a) => detectAttachmentType(a) !== 'jira');

  return (
    <div className="jira-att-panel">
      {/* Header row with refresh */}
      <div className="jira-att-panel__toolbar">
        <span className="jira-att-panel__label">
          {attachments.length > 0 ? t('jira.attachmentCount', { count: attachments.length }) : t('jira.attachments')}
        </span>
        {jiraUrl && (
          <button
            className="jira-att-panel__refresh-btn"
            onClick={handleRefresh}
            disabled={refreshing || loading}
            title={t('jira.refreshTitle')}
          >
            <IconRefresh spinning={refreshing} />
            {refreshing ? t('jira.refreshing') : t('jira.refresh')}
          </button>
        )}
      </div>

      {refreshError && <div className="jira-att-panel__error">{refreshError}</div>}

      {loading ? (
        <div className="jira-att-panel__loading">
          <span className="jira-att-panel__spinner" />
          {t('common:loading')}
        </div>
      ) : attachments.length === 0 ? (
        <p className="jira-att-panel__empty">{t('jira.empty')}</p>
      ) : (
        <div className="jira-att-panel__list">
          {jiraAttachment && <AttachmentCard key={jiraAttachment.id} attachment={jiraAttachment} onView={setViewing} />}
          {others.map((a) => (
            <AttachmentCard key={a.id} attachment={a} onView={setViewing} />
          ))}
        </div>
      )}

      {viewing && <ContentDrawer attachment={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
};

export default JiraAttachmentPanel;
