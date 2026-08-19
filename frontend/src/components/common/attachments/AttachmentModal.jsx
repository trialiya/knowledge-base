import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import attachmentApi from '@/api/attachmentApi';
import ModalShell from '@/components/common/modal/ModalShell';
import '@/components/common/ui/buttons.css';

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
  // null — текст ещё не пришёл; отсюда же и `loading`, отдельным состоянием он
  // был бы вторым проходом рендера на каждое открытие.
  const [content, setContent] = useState(null);

  const [req, setReq] = useState({ isContent, id: attachment.id });
  if (req.isContent !== isContent || req.id !== attachment.id) {
    setReq({ isContent, id: attachment.id });
    setContent(null);
  }

  useEffect(() => {
    if (!isContent) return undefined;

    let cancelled = false;
    attachmentApi
      .getContent(attachment.id)
      .then((text) => {
        if (!cancelled) setContent(text);
      })
      .catch(() => {
        if (!cancelled) setContent(t('attachments.errorLoadContent'));
      });

    return () => {
      cancelled = true;
    };
  }, [isContent, attachment.id, t]);

  const loading = isContent && content === null;

  const title = isContent ? attachment.fileName : t('attachments.descriptionTitle', { name: attachment.fileName });
  const body = isContent ? content : attachment.summary || t('attachments.noDescription');

  return (
    <ModalShell onClose={onClose} className="attachment-viewer">
      <div className="attachment-viewer__header">
        <span className="attachment-viewer__name">{title}</span>
        <button className="icon-btn" onClick={onClose} title={t('close')}>
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
    </ModalShell>
  );
};

export default AttachmentModal;
