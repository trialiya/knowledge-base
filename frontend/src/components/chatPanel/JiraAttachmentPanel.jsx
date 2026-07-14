import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import './jiraAttachmentPanel.css';
import chatApi from '../../api/chatApi';
import { formatFileSize } from '../../utils/formatting';
import { IconJira, IconConfluence, IconDoc, IconRefreshCw, IconEye, IconX, IconLink } from '../../icons';

// ─── Helpers ──────────────────────────────────────────────────────────────────

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
            <IconX />
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
          {type === 'jira' ? <IconJira /> : type === 'confluence' ? <IconConfluence /> : <IconDoc size={14} />}
        </span>
        <span className="jira-att-card__name" title={attachment.fileName}>
          {attachment.fileName}
        </span>
        <span className="jira-att-card__size">{formatFileSize(attachment.fileSize)}</span>
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
          <IconLink size={11} /> {t('jira.openSource')}
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
      const data = await chatApi.listAttachments(conversationId);
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
      const res = await chatApi.refreshJira(conversationId, jiraUrl);
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
            <IconRefreshCw spinning={refreshing} />
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
