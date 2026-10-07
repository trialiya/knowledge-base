import { memo, useState, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useTranslation } from 'react-i18next';
import DocLinkTooltip from '@/components/common/preview/DocLinkTooltip';
import '@/components/common/ui/buttons.css';
import '../styles/message.css';
import MarkdownCodeBlock from '@/components/common/ui/MarkdownCodeBlock';
import ToolCallNotifications from './ToolCallNotifications';
import UserMessageText from './UserMessageText';
import MessageContextItems from './MessageContextItems';
import { formatTokens, hasUsage, usageTooltip } from './tokenUsage';
import CopyButton from '@/components/common/ui/CopyButton';
import { SENDER } from '@/constants/messageSender';
import RelativeTime from '@/components/common/ui/RelativeTime';
import { formatLongDateTime } from '@/utils/formatting';

// ─── Markdown components (стиль KnowledgeBase .md-preview) ─────────────────────
// Вынесено в фабрику, чтобы ссылки получали onNavigateToDoc через замыкание.

function getMarkdownComponents(onNavigateToDoc) {
  return {
    // `node` — служебный проп react-markdown: в спреде он уехал бы на DOM-узел
    // атрибутом node="[object Object]".
    a: ({ href, children, node: _node, ...props }) => (
      <DocLinkTooltip href={href} onNavigate={onNavigateToDoc} {...props}>
        {children}
      </DocLinkTooltip>
    ),
    pre: MarkdownCodeBlock,
  };
}

const Message = ({
  text,
  sender,
  toolCalls,
  error,
  onRetry,
  conversationId,
  onNavigateToDoc,
  timestamp,
  mid,
  searchActive,
  contextItems,
  queued = false,
  modelLabel,
  usage,
  contextTokens,
}) => {
  const { t } = useTranslation('chat');
  const [showSource, setShowSource] = useState(false);
  const messageClass =
    `message ${sender}` +
    (error && sender === SENDER.AI ? ' message--error' : '') +
    // Отправлено во время ответа и ещё не доставлено в историю: пузырь приглушён, пока
    // прогон не дойдёт до места, где вопрос можно вставить (см. useChatRun.queueMessage).
    (queued ? ' message--queued' : '');
  const hasToolCalls = toolCalls && toolCalls.length > 0;

  // Стабильные идентичности markdown-компонентов между рендерами (как в
  // MarkdownEditor). Без useMemo каждый рендер создаёт новую функцию `a`, React
  // считает её другим типом и пересоздаёт DOM-поддеревья ссылок с новыми
  // текстовыми узлами. Это ломает CSS Highlight подсветку find-бара: её Range-ы
  // держат ссылки на старые узлы, и совпадения гасли при любом ре-рендере
  // списка (например, setShowScrollButton после плавного скролла к совпадению).
  const mdComponents = useMemo(() => getMarkdownComponents(onNavigateToDoc), [onNavigateToDoc]);

  // Разбивка — в подсказке: в футере на неё нет места, а нужна она редко. Сверху три числа про
  // сам разговор, снизу — total input, который без строки про кэш выглядит необъяснимо большим.
  const usageTitle = hasUsage(usage) ? usageTooltip(usage, t, 'message.tokensContext') : undefined;
  // Контекст после обращения к модели, написавшего этот сегмент, — у сегментов, которые плашкой
  // итога не отмечены: у последнего ответа прогона то же число уже стоит в самой плашке.
  const contextTitle =
    sender === SENDER.AI && !hasUsage(usage) && Number(contextTokens) > 0
      ? t('message.contextAfterMessage', { context: formatTokens(contextTokens) })
      : undefined;

  // Пузырь — только контент сообщения, без футера
  const bubble = (
    <div className={messageClass}>
      {sender === SENDER.AI ? (
        showSource ? (
          <pre className="message-raw-source">{text}</pre>
        ) : (
          <div className="md-preview md-preview--chat">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={mdComponents}>
              {text}
            </ReactMarkdown>
          </div>
        )
      ) : (
        <>
          <UserMessageText text={text} />
          <MessageContextItems items={contextItems} />
          {queued && (
            <div className="message-queued-note" role="status">
              {t('message.queued')}
            </div>
          )}
        </>
      )}
    </div>
  );

  // Футер под пузырём: AI — время слева, кнопки справа;
  // user — кнопка слева, время справа.
  const footer =
    sender === SENDER.AI ? (
      <div className="message-footer message-footer--ai">
        <div className="message-footer__meta">
          <RelativeTime className="message-footer__time" value={timestamp} formatTitle={formatLongDateTime} />
          {/* Модель этого ответа — не та, что выбрана в чате сейчас (её показывает вкладка
              «Инфо»): модель переключают посреди чата, и старые ответы остаются за прежней. */}
          {modelLabel && (
            <span className="message-footer__model" title={t('message.answeredBy', { model: modelLabel })}>
              {modelLabel}
            </span>
          )}
          {/* Токены всего прогона, а не этого сегмента: ответ с инструментами — это несколько
              обращений к модели, и плашка стоит на последнем его пузыре. В ней занятый контекст:
              total input больше в разы и в футере читался бы как размер одного ответа. */}
          {hasUsage(usage) && (
            <span className="message-footer__tokens" title={usageTitle}>
              {t('message.tokens', { context: formatTokens(usage.contextTokens) })}
            </span>
          )}
        </div>
        <div className="message-footer__actions">
          {error && onRetry && (
            <button
              className="btn btn--xs btn--danger"
              onClick={() => onRetry(mid)}
              title={t('message.retry')}
              type="button"
            >
              ↻ {t('message.retry')}
            </button>
          )}
          {/* Пустой текст кнопку не убирает: в футере она стоит в ряду с
              «повторить» и «исходник», и исчезающая кнопка пересобирала бы ряд. */}
          <CopyButton
            value={text ?? ''}
            keepEmpty
            title={t('message.copyMessage')}
            className="icon-btn--sm icon-btn--quiet"
          />
          <button
            type="button"
            className="btn btn--xs btn--ghost"
            aria-pressed={showSource}
            onClick={() => setShowSource((v) => !v)}
            title={showSource ? t('message.viewFormatted') : t('message.viewSource')}
          >
            {showSource ? `◈ ${t('message.btnMarkdown')}` : `{ } ${t('message.btnSource')}`}
          </button>
        </div>
      </div>
    ) : (
      <div className="message-footer message-footer--user">
        <div className="message-footer__actions">
          {/* У вопроса кнопка бывает одна — ответить на него: на неотвеченный вопрос её
              передаёт MessageList (см. unansweredQuestionMid). */}
          {onRetry && (
            <>
              <span className="message-footer__unanswered">{t('message.unanswered')}</span>
              <button
                className="btn btn--xs btn--ghost"
                onClick={() => onRetry(mid)}
                title={t('message.answerHint')}
                type="button"
              >
                ↻ {t('message.answer')}
              </button>
            </>
          )}
          <CopyButton
            value={text ?? ''}
            keepEmpty
            title={t('message.copyMessage')}
            className="icon-btn--sm icon-btn--quiet"
          />
        </div>
        <RelativeTime className="message-footer__time" value={timestamp} formatTitle={formatLongDateTime} />
      </div>
    );

  // Сегмент из одних вызовов инструментов (модель не написала текста перед tool_calls):
  // пустой пузырь и футер не рисуем — остаются только плашки вызовов.
  const toolCallsOnly = hasToolCalls && sender === SENDER.AI && !(text || '').trim();

  const messageBlock = (
    <div
      className={`message-block message-block--${sender}${searchActive ? ' message-block--search-hit' : ''}`}
      data-mid={mid ?? undefined}
      title={contextTitle}
    >
      {!toolCallsOnly && bubble}
      {!toolCallsOnly && footer}
      {hasToolCalls && sender === SENDER.AI && (
        <ToolCallNotifications toolCalls={toolCalls} conversationId={conversationId} />
      )}
    </div>
  );

  // Блоки «изменения документов/файлов» рендерит MessageList — одним блоком
  // в конце всего ответа (после последнего сегмента), а не под каждым сегментом.
  return messageBlock;
};

/**
 * Мемоизация здесь не микрооптимизация: на каждый чанк стрима лента получает новый массив
 * сообщений, и без неё markdown перепарсивался бы у всех пузырей разговора по нескольку раз
 * в секунду. Пузыри — обычные объекты состояния (редьюсер заменяет только изменившийся),
 * поэтому поверхностного сравнения хватает; onRetry и onNavigateToDoc приходят сверху
 * стабильными (useCallback), а не замыканием на сообщение.
 */
export default memo(Message);
