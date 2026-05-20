import React, { useState, useEffect, useCallback, useRef } from 'react';
import { IconTrash, IconDoc } from './icons';

// ─── Icons specific to attachments ───────────────────────────────────────────

export const IconUpload = ({ size = 14 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </svg>
);

export const IconPaperclip = ({ size = 14 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
  </svg>
);

const IconSummarize = ({ size = 13 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <line x1="4" y1="6" x2="20" y2="6" />
    <line x1="4" y1="10" x2="14" y2="10" />
    <line x1="4" y1="14" x2="18" y2="14" />
    <line x1="4" y1="18" x2="10" y2="18" />
  </svg>
);

const IconEye = ({ size = 13 }) => (
  <svg
    width={size}
    height={size}
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

// ─── API helpers ─────────────────────────────────────────────────────────────

const attachmentApi = {
  list: (ownerType, ownerId) =>
    fetch(`/api/${ownerType === 'document' ? 'documents' : 'chat'}/${ownerId}/attachments`).then((r) => r.json()),
  upload: (ownerType, ownerId, file) => {
    const formData = new FormData();
    formData.append('file', file);
    return fetch(`/api/${ownerType === 'document' ? 'documents' : 'chat'}/${ownerId}/attachments`, {
      method: 'POST',
      body: formData,
    }).then((r) => {
      if (!r.ok) throw new Error(`Upload failed: ${r.status}`);
      return r.json();
    });
  },
  delete: (id) => fetch(`/api/attachments/${id}`, { method: 'DELETE' }),
  summarize: (id) => fetch(`/api/attachments/${id}/summarize`, { method: 'POST' }).then((r) => r.json()),
  getContent: (id) => fetch(`/api/attachments/${id}/content`).then((r) => r.text()),
};

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ─── Content viewer modal ────────────────────────────────────────────────────

const ContentViewer = ({ attachment, onClose }) => {
  const [content, setContent] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    attachmentApi
      .getContent(attachment.id)
      .then((text) => {
        if (!cancelled) {
          setContent(text);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setContent('Ошибка загрузки содержимого');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [attachment.id]);

  return (
    <div className="attachment-viewer-overlay" onClick={onClose}>
      <div className="attachment-viewer" onClick={(e) => e.stopPropagation()}>
        <div className="attachment-viewer__header">
          <span className="attachment-viewer__name">{attachment.fileName}</span>
          <button className="detail-icon-btn" onClick={onClose} title="Закрыть">
            ✕
          </button>
        </div>
        <div className="attachment-viewer__body">
          {loading ? (
            <p className="attachment-viewer__loading">Загрузка…</p>
          ) : (
            <pre className="attachment-viewer__content">{content}</pre>
          )}
        </div>
      </div>
    </div>
  );
};

// компонент для просмотра полного текста summary в модальном окне
const SummaryViewer = ({ attachment, onClose }) => {
  return (
    <div className="attachment-viewer-overlay" onClick={onClose}>
      <div className="attachment-viewer" onClick={(e) => e.stopPropagation()}>
        <div className="attachment-viewer__header">
          <span className="attachment-viewer__name">Описание: {attachment.fileName}</span>
          <button className="detail-icon-btn" onClick={onClose} title="Закрыть">
            ✕
          </button>
        </div>
        <div className="attachment-viewer__body">
          <pre className="attachment-viewer__content">{attachment.summary || 'Нет описания'}</pre>
        </div>
      </div>
    </div>
  );
};

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
  const [attachments, setAttachments] = useState([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [summarizingId, setSummarizingId] = useState(null);
  const [viewingAttachment, setViewingAttachment] = useState(null);
  const [viewingSummaryFor, setViewingSummaryFor] = useState(null);
  const fileInputRef = useRef(null);

  // ── Load attachments ────────────────────────────────────────────────────

  const loadAttachments = useCallback(async () => {
    if (!ownerId) return;
    setLoading(true);
    try {
      const data = await attachmentApi.list(ownerType, ownerId);
      setAttachments(Array.isArray(data) ? data : []);
      onCountChange?.(Array.isArray(data) ? data.length : 0);
    } catch {
      setAttachments([]);
      onCountChange?.(0);
    } finally {
      setLoading(false);
    }
  }, [ownerType, ownerId, onCountChange]);

  useEffect(() => {
    loadAttachments();
  }, [loadAttachments]);

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
      alert('Ошибка загрузки файла. Поддерживаются только текстовые файлы.');
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

  const handleDelete = async (id) => {
    if (!window.confirm('Удалить вложение?')) return;
    try {
      await attachmentApi.delete(id);
      setAttachments((prev) => {
        const next = prev.filter((a) => a.id !== id);
        onCountChange?.(next.length);
        return next;
      });
    } catch {
      alert('Ошибка удаления');
    }
  };

  // ── Summarize ───────────────────────────────────────────────────────────

  const handleSummarize = async (id) => {
    setSummarizingId(id);
    try {
      const updated = await attachmentApi.summarize(id);
      setAttachments((prev) => prev.map((a) => (a.id === id ? updated : a)));
    } catch {
      alert('Ошибка создания описания');
    } finally {
      setSummarizingId(null);
    }
  };

  // ── Compact mode (for summary tab) ──────────────────────────────────────

  if (compact) {
    if (loading) return <p className="attachment-compact__loading">Загрузка…</p>;
    if (attachments.length === 0) return <p className="attachment-compact__empty">Нет вложений</p>;

    return (
      <div className="attachment-compact-list">
        {attachments.map((a) => (
          <div key={a.id} className="attachment-compact-item">
            <IconDoc size={13} />
            <span className="attachment-compact-item__name">{a.fileName}</span>
            <span className="attachment-compact-item__size">{formatFileSize(a.fileSize)}</span>
            {a.summary && (
              <span
                className="attachment-compact-item__summary"
                title="Кликните для просмотра полного описания"
                onClick={() => setViewingSummaryFor(a)} // ✨ NEW
                style={{ cursor: 'pointer' }}
              >
                {a.summary.length > 60 ? a.summary.slice(0, 60) + '…' : a.summary}
              </span>
            )}
          </div>
        ))}
        {/* Модалка для просмотра summary в компактном режиме */}
        {viewingSummaryFor && (
          <SummaryViewer attachment={viewingSummaryFor} onClose={() => setViewingSummaryFor(null)} />
        )}
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
        <span>{uploading ? 'Загрузка…' : 'Перетащите файл или нажмите для выбора'}</span>
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
        <p className="attachment-panel__loading">Загрузка вложений…</p>
      ) : attachments.length === 0 ? (
        <p className="attachment-panel__empty">Нет вложений</p>
      ) : (
        <div className="attachment-table">
          <div className="attachment-table__header">
            <span />
            <span>Имя файла</span>
            <span>Размер</span>
            <span>Действия</span>
          </div>
          {attachments.map((a) => (
            <div key={a.id} className="attachment-row">
              <span className="attachment-row__icon">
                <IconDoc size={13} />
              </span>
              <span className="attachment-row__name-wrap">
                <span className="attachment-row__name">{a.fileName}</span>
                {a.summary && (
                  <span
                    className="attachment-row__summary"
                    onClick={() => setViewingSummaryFor(a)}
                    title="Кликните для просмотра полного описания"
                    style={{ cursor: 'pointer' }}
                  >
                    {a.summary}
                  </span>
                )}
                {!a.summary && <span className="attachment-row__no-summary">Нет описания</span>}
              </span>
              <span className="attachment-row__size">{formatFileSize(a.fileSize)}</span>
              <span className="attachment-row__actions">
                <button className="detail-icon-btn" title="Просмотреть" onClick={() => setViewingAttachment(a)}>
                  <IconEye />
                </button>
                <button
                  className="detail-icon-btn"
                  title={a.summary ? 'Обновить описание' : 'Создать описание'}
                  onClick={() => handleSummarize(a.id)}
                  disabled={summarizingId === a.id}
                >
                  {summarizingId === a.id ? '⏳' : <IconSummarize />}
                </button>
                <button
                  className="detail-icon-btn attachment-row__delete"
                  title="Удалить"
                  onClick={() => handleDelete(a.id)}
                >
                  <IconTrash />
                </button>
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Content viewer */}
      {viewingAttachment && <ContentViewer attachment={viewingAttachment} onClose={() => setViewingAttachment(null)} />}

      {/* Модалка для просмотра summary в полном режиме */}
      {viewingSummaryFor && <SummaryViewer attachment={viewingSummaryFor} onClose={() => setViewingSummaryFor(null)} />}
    </div>
  );
};

export default AttachmentPanel;
