import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import attachmentApi from '../../api/attachmentApi';

/**
 * Single modal for viewing an attachment's content or its summary.
 *
 * Props:
 *   attachment – the attachment record
 *   mode       – "content" (fetches text from the server) | "summary" (shows stored summary)
 *   onClose()  – close handler
 */
const AttachmentModal = ({ attachment, mode, onClose }) => {
  const { t } = useTranslation();
  const isContent = mode === 'content';
  const [content, setContent] = useState(null);
  const [loading, setLoading] = useState(isContent);

  useEffect(() => {
    if (!isContent) return undefined;

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
          setContent(t('attachments.errorLoadContent'));
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [isContent, attachment.id, t]);

  const title = isContent ? attachment.fileName : t('attachments.descriptionTitle', { name: attachment.fileName });
  const body = isContent ? content : attachment.summary || t('attachments.noDescription');

  return (
    <div className="attachment-viewer-overlay" onClick={onClose}>
      <div className="attachment-viewer" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="attachment-viewer__header">
          <span className="attachment-viewer__name">{title}</span>
          <button className="detail-icon-btn" onClick={onClose} title={t('close')}>
            ✕
          </button>
        </div>
        <div className="attachment-viewer__body">
          {loading ? (
            <p className="attachment-viewer__loading">{t('loading')}</p>
          ) : (
            <pre className="attachment-viewer__content">{body}</pre>
          )}
        </div>
      </div>
    </div>
  );
};

export default AttachmentModal;
