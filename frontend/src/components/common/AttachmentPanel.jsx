import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconTrash, IconDoc, IconEye, IconUpload, IconSummarize } from '../knowledgeBasePanel/icons';
import attachmentApi, { formatFileSize } from './attachmentApi';
import AttachmentModal from './AttachmentModal';
import ConfirmModal from './ConfirmModal';
import ErrorModal from './ErrorModal';

// ─── Main component ──────────────────────────────────────────────────────────

/**
 * Shared attachment panel for documents and chats.
 *
 * Props:
 *   ownerType  — "document" | "chat"
 *   ownerId    — document id (number/string) or conversationId (string)
 *   compact    — if true, renders a minimal list (for summary tab)
 *   onCountChange — optional callback (count) when attachment count changes
 */
const AttachmentPanel = ({ ownerType, ownerId, compact = false, onCountChange }) => {
  const { t } = useTranslation();
  const [attachments, setAttachments] = useState([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [summarizingId, setSummarizingId] = useState(null);

  // Unified viewer state: { attachment, mode: 'content' | 'summary' } | null
  const [viewing, setViewing] = useState(null);
  // Pending delete awaiting confirmation: attachment id | null
  const [pendingDelete, setPendingDelete] = useState(null);
  const [error, setError] = useState(null); // string | null

  const fileInputRef = useRef(null);
  // Tracks the owner we've already kicked off a load for, so the fetch fires
  // once per owner — not twice under StrictMode, and not again on unrelated
  // parent re-renders.
  const loadedOwnerRef = useRef(null);

  const showContent = (a) => setViewing({ attachment: a, mode: 'content' });
  const showSummary = (a) => setViewing({ attachment: a, mode: 'summary' });

  // ── Load attachments ────────────────────────────────────────────────────

  const loadAttachments = useCallback(async () => {
    if (!ownerId) return;
    setLoading(true);
    try {
      const data = await attachmentApi.list(ownerType, ownerId);
      const list = Array.isArray(data) ? data : [];
      setAttachments(list);
      onCountChange?.(list.length);
    } catch {
      setAttachments([]);
      onCountChange?.(0);
    } finally {
      setLoading(false);
    }
  }, [ownerType, ownerId, onCountChange]);

  useEffect(() => {
    const ownerKey = `${ownerType}:${ownerId}`;
    if (!ownerId || loadedOwnerRef.current === ownerKey) return;
    loadedOwnerRef.current = ownerKey;
    loadAttachments();
  }, [ownerType, ownerId, loadAttachments]);

  // ── Upload ──────────────────────────────────────────────────────────────

  const handleUpload = async (file) => {
    if (!file || !ownerId) return;
    setUploading(true);
    try {
      const newAttachment = await attachmentApi.upload(ownerType, ownerId, file);
      setAttachments((prev) => {
        const next = [...prev, newAttachment];
        onCountChange?.(next.length);
        return next;
      });
    } catch (err) {
      console.error('Upload error:', err);
      setError(t('attachments.errorUpload'));
    } finally {
      setUploading(false);
    }
  };

  const handleFileSelect = (e) => {
    const file = e.target.files?.[0];
    if (file) handleUpload(file);
    e.target.value = '';
  };

  // ── Drag & drop ─────────────────────────────────────────────────────────

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragOver(true);
  };
  const handleDragLeave = () => setDragOver(false);
  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) handleUpload(file);
  };

  // ── Delete ──────────────────────────────────────────────────────────────

  const confirmDelete = async () => {
    const id = pendingDelete;
    setPendingDelete(null);
    if (id == null) return;
    try {
      await attachmentApi.delete(id);
      setAttachments((prev) => {
        const next = prev.filter((a) => a.id !== id);
        onCountChange?.(next.length);
        return next;
      });
    } catch {
      setError(t('attachments.errorDelete'));
    }
  };

  // ── Summarize ───────────────────────────────────────────────────────────

  const handleSummarize = async (id) => {
    setSummarizingId(id);
    try {
      const updated = await attachmentApi.summarize(id);
      setAttachments((prev) => prev.map((a) => (a.id === id ? updated : a)));
    } catch {
      setError(t('attachments.errorSummarize'));
    } finally {
      setSummarizingId(null);
    }
  };

  // ── Shared modals (rendered in every mode) ────────────────────────────────

  const modals = (
    <>
      {viewing && (
        <AttachmentModal attachment={viewing.attachment} mode={viewing.mode} onClose={() => setViewing(null)} />
      )}
      <ConfirmModal
        open={pendingDelete != null}
        icon="🗑️"
        title={t('attachments.deleteConfirmTitle')}
        message={t('attachments.deleteConfirmMessage')}
        confirmLabel={t('delete')}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
      <ErrorModal open={!!error} icon="⚠️" title={t('error')} message={error || ''} onClose={() => setError(null)} />
    </>
  );

  // ── Compact mode (for summary tab) ──────────────────────────────────────

  if (compact) {
    if (loading) return <p className="attachment-compact__loading">{t('loading')}</p>;
    if (attachments.length === 0) return <p className="attachment-compact__empty">{t('attachments.empty')}</p>;

    return (
      <div className="attachment-compact-list">
        {attachments.map((a) => (
          <div key={a.id} className="attachment-compact-item">
            <IconDoc size={13} />
            <span className="attachment-compact-item__name">{a.fileName}</span>
            <span className="attachment-compact-item__size">{formatFileSize(a.fileSize)}</span>
            {a.summary && (
              <span
                className="attachment-compact-item__summary attachment-summary--clickable"
                title={t('attachments.summaryHint')}
                onClick={() => showSummary(a)}
              >
                {a.summary.length > 60 ? a.summary.slice(0, 60) + '…' : a.summary}
              </span>
            )}
          </div>
        ))}
        {modals}
      </div>
    );
  }

  // ── Full mode ───────────────────────────────────────────────────────────

  return (
    <div className="attachment-panel">
      {/* Drop zone */}
      <div
        className={`attachment-dropzone ${dragOver ? 'attachment-dropzone--active' : ''} ${
          uploading ? 'attachment-dropzone--uploading' : ''
        }`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
      >
        <IconUpload size={20} />
        <span>{uploading ? t('attachments.uploading') : t('attachments.dropzone')}</span>
        <input
          ref={fileInputRef}
          type="file"
          style={{ display: 'none' }}
          onChange={handleFileSelect}
          accept="text/*,.md,.json,.yaml,.yml,.xml,.csv,.log,.sql,.gradle,.java,.js,.jsx,.ts,.tsx,.py,.go,.rs,.html,.css"
        />
      </div>

      {/* List */}
      {loading ? (
        <p className="attachment-panel__loading">{t('attachments.loadingList')}</p>
      ) : attachments.length === 0 ? (
        <p className="attachment-panel__empty">{t('attachments.empty')}</p>
      ) : (
        <div className="attachment-table">
          <div className="attachment-table__header">
            <span />
            <span>{t('attachments.colName')}</span>
            <span>{t('attachments.colSize')}</span>
            <span>{t('attachments.colActions')}</span>
          </div>
          {attachments.map((a) => (
            <div key={a.id} className="attachment-row">
              <span className="attachment-row__icon">
                <IconDoc size={13} />
              </span>
              <span className="attachment-row__name-wrap">
                <span className="attachment-row__name">{a.fileName}</span>
                {a.sourceUrl && (
                  <a
                    className="attachment-row__source"
                    href={a.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(e) => e.stopPropagation()}
                    title={a.sourceUrl}
                  >
                    🔗 {t('attachments.source')}
                  </a>
                )}
                {a.summary ? (
                  <span
                    className="attachment-row__summary attachment-summary--clickable"
                    onClick={() => showSummary(a)}
                    title={t('attachments.summaryHint')}
                  >
                    {a.summary}
                  </span>
                ) : (
                  <span className="attachment-row__no-summary">{t('attachments.noDescription')}</span>
                )}
              </span>
              <span className="attachment-row__size">{formatFileSize(a.fileSize)}</span>
              <span className="attachment-row__actions">
                <button className="detail-icon-btn" title={t('attachments.viewTitle')} onClick={() => showContent(a)}>
                  <IconEye />
                </button>
                <button
                  className="detail-icon-btn"
                  title={a.summary ? t('attachments.summarizeUpdate') : t('attachments.summarizeCreate')}
                  onClick={() => handleSummarize(a.id)}
                  disabled={summarizingId === a.id}
                >
                  {summarizingId === a.id ? '⏳' : <IconSummarize />}
                </button>
                <button
                  className="detail-icon-btn attachment-row__delete"
                  title={t('attachments.deleteTitle')}
                  onClick={() => setPendingDelete(a.id)}
                >
                  <IconTrash />
                </button>
              </span>
            </div>
          ))}
        </div>
      )}

      {modals}
    </div>
  );
};

export default AttachmentPanel;
