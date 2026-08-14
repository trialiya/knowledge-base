import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconTrash, IconDoc, IconEye, IconUpload, IconSummarize } from '../../icons';
import { UPLOAD_ACCEPT } from '../../constants/uploadAccept';
import { formatFileSize } from '../../utils/formatting';
import useAttachments from './useAttachments';
import AttachmentModal from './AttachmentModal';
import ConfirmModal from './ConfirmModal';
import ErrorModal from './ErrorModal';
import './attachmentPanel.css';

/**
 * Панель вложений — общая для документов и чатов. Список и операции над ним
 * держит useAttachments, здесь только рендер и локальное состояние диалогов.
 *
 * Props:
 *   ownerType  — "document" | "chat"
 *   ownerId    — document id (number/string) или conversationId (string)
 *   onCountChange — (count) => void: число вложений изменилось
 *   refreshSignal — bump, чтобы перечитать список: вложения владельца изменились
 *                   мимо панели (композер чата тоже прикрепляет и отменяет файлы)
 *   onDeleted  — (id) => void после того, как файла действительно не стало: для
 *                тех, кто ещё держит на него ссылку (чипы в композере чата)
 */
const AttachmentPanel = ({ ownerType, ownerId, onCountChange, refreshSignal = 0, onDeleted }) => {
  const { t } = useTranslation();
  const { attachments, loading, uploading, summarizingId, errorKey, dismissError, upload, remove, summarize } =
    useAttachments({ ownerType, ownerId, refreshSignal, onCountChange, onDeleted });

  // Открытый просмотрщик: { attachment, mode: 'content' | 'summary' } | null
  const [viewing, setViewing] = useState(null);
  // Удаление, ждущее подтверждения: id вложения | null
  const [pendingDelete, setPendingDelete] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef(null);

  const showContent = (a) => setViewing({ attachment: a, mode: 'content' });
  const showSummary = (a) => setViewing({ attachment: a, mode: 'summary' });

  const handleFileSelect = (e) => {
    const file = e.target.files?.[0];
    if (file) upload(file);
    e.target.value = '';
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragOver(true);
  };
  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) upload(file);
  };

  const confirmDelete = () => {
    const id = pendingDelete;
    setPendingDelete(null);
    if (id != null) remove(id);
  };

  return (
    <div className="attachment-panel">
      {/* Зона загрузки. Кнопка, а не div с onClick: сюда попадают и с клавиатуры,
          а input лежит снаружи — внутри кнопки он был бы невалидной вложенностью. */}
      <button
        type="button"
        className={`attachment-dropzone ${dragOver ? 'attachment-dropzone--active' : ''} ${
          uploading ? 'attachment-dropzone--uploading' : ''
        }`}
        onDragOver={handleDragOver}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
      >
        <IconUpload size={20} />
        <span>{uploading ? t('attachments.uploading') : t('attachments.dropzone')}</span>
      </button>
      <input ref={fileInputRef} type="file" hidden onChange={handleFileSelect} accept={UPLOAD_ACCEPT} />

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
                <button className="icon-btn" title={t('attachments.viewTitle')} onClick={() => showContent(a)}>
                  <IconEye />
                </button>
                <button
                  className="icon-btn"
                  title={a.summary ? t('attachments.summarizeUpdate') : t('attachments.summarizeCreate')}
                  onClick={() => summarize(a.id)}
                  disabled={summarizingId === a.id}
                >
                  {summarizingId === a.id ? '⏳' : <IconSummarize />}
                </button>
                <button
                  className="icon-btn attachment-row__delete"
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
      <ErrorModal
        open={!!errorKey}
        icon="⚠️"
        title={t('error')}
        message={errorKey ? t(errorKey) : ''}
        onClose={dismissError}
      />
    </div>
  );
};

export default AttachmentPanel;
