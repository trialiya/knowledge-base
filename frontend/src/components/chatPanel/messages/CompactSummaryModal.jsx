import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useTranslation } from 'react-i18next';
import chatApi from '@/api/chatApi';
import ModalShell from '@/components/common/modal/ModalShell';
import CopyButton from '@/components/common/ui/CopyButton';
import '@/components/common/ui/buttons.css';
import '../styles/compact.css';

/**
 * Сводка одного сжатия — то, чем модель заменила весь предыдущий контекст. Текст тянется по
 * запросу, а не приезжает со страницей истории: он бывает в десятки килобайт, а открывают его
 * изредка и по одной.
 *
 * Текст — markdown (сводку пишет модель по разделам, см. `prompt/compactor.md`), поэтому
 * показываем его тем же `md-preview`, что и ответы ассистента.
 */
const CompactSummaryModal = ({ conversationId, messageId, onClose }) => {
  const { t } = useTranslation('chat');
  // Ответ сервера; null — запрос ещё идёт. loading/error выводятся из него при рендере.
  const [answer, setAnswer] = useState(null); // { detail, failed } | null

  useEffect(() => {
    let cancelled = false;
    chatApi
      .getCompactDetail(conversationId, messageId)
      .then((detail) => {
        if (!cancelled) setAnswer({ detail: detail || null, failed: false });
      })
      .catch((error) => {
        // 404 здесь осмысленный ответ, а не сбой: плашка есть, а сводки за ней нет
        // (её ряд не нашёлся) — об этом и говорим отдельной строкой.
        if (!cancelled) setAnswer({ detail: null, failed: error?.status !== 404 });
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, messageId]);

  const loading = answer === null;
  const detail = answer?.detail ?? null;
  const failed = !!answer?.failed;

  return (
    <ModalShell onClose={onClose} className="compact-summary">
      <div className="compact-summary__header">
        <span className="compact-summary__title">
          <span aria-hidden="true">🗜️</span> {t('compact.summaryTitle')}
        </span>
        <CopyButton value={detail?.summary} />
        <button type="button" className="icon-btn" onClick={onClose} title={t('close')}>
          ✕
        </button>
      </div>

      {loading && <div className="compact-summary__notice">{t('loading')}</div>}
      {!loading && !detail && (
        <div className="compact-summary__notice compact-summary__notice--error">
          {failed ? t('compact.summaryLoadError') : t('compact.summaryNotFound')}
        </div>
      )}

      {detail && (
        <div className="compact-summary__body">
          <div className="compact-summary__stats">
            {t('compact.done', { messages: detail.messages })}{' '}
            {t('compact.summaryChars', { chars: detail.summaryChars })}
          </div>
          <div className="md-preview md-preview--chat">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{detail.summary}</ReactMarkdown>
          </div>
        </div>
      )}
    </ModalShell>
  );
};

export default CompactSummaryModal;
