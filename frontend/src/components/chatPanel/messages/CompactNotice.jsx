import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import CompactSummaryModal from './CompactSummaryModal';
import '../styles/compact.css';

/**
 * След команды `/compact` в ленте: сколько сообщений свернулось в сводку и кнопка, открывающая
 * саму сводку.
 *
 * Плашка приходит из истории отдельным сообщением (`compact` в его мете — см.
 * `SummaryWriter.writeCompacted`), поэтому она переживает перезагрузку страницы и выглядит
 * одинаково в живом потоке и после неё. Пузырём с текстом её не рисуем: сжатие — это не реплика
 * ассистента, а событие с самим чатом, и разделитель читается как событие.
 *
 * @param messageId id строки-плашки — адрес сводки. Без него (очень старый прогон, чьи события
 *   переигрались без id) плашка остаётся, а кнопка деталей не рисуется: открывать нечего.
 */
const CompactNotice = ({ conversationId, messageId, compact, timestamp }) => {
  const { t, i18n } = useTranslation('chat');
  const [showSummary, setShowSummary] = useState(false);
  const canOpen = !!(conversationId && messageId != null);
  const time = timestamp ? new Date(timestamp) : null;
  const title = time && !isNaN(time) ? time.toLocaleString(i18n.language) : undefined;

  return (
    <div className="compact-notice" role="note" title={title}>
      <span className="compact-notice__icon" aria-hidden="true">
        🗜️
      </span>
      <span className="compact-notice__text">{t('compact.done', { messages: compact.messages })}</span>
      {canOpen && (
        <button type="button" className="compact-notice__details" onClick={() => setShowSummary(true)}>
          {t('compact.details')}
        </button>
      )}
      {showSummary && canOpen && (
        <CompactSummaryModal
          conversationId={conversationId}
          messageId={messageId}
          onClose={() => setShowSummary(false)}
        />
      )}
    </div>
  );
};

export default CompactNotice;
