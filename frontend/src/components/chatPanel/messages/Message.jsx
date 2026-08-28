import { useState, useEffect, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useTranslation } from 'react-i18next';
import DocLinkTooltip from '@/components/common/preview/DocLinkTooltip';
import '../styles/message.css';
import CodeBlock from '@/components/common/ui/CodeBlock';
import ToolCallNotifications from './ToolCallNotifications';
import MessageContextItems from './MessageContextItems';
import { billedTokens, cacheShare, formatTokens, hasUsage } from './tokenUsage';
import { IconCopySmall, IconCopied } from '@/icons/index';
import useCopyFeedback from '@/components/common/ui/useCopyFeedback';
import { SENDER } from '@/constants/messageSender';

/** Кнопка «копировать всё сообщение» — копирует исходный текст сообщения. */
const MessageCopyButton = ({ text }) => {
  const { t } = useTranslation('chat');
  const [copied, copy] = useCopyFeedback();

  return (
    <button
      className={`message-copy-btn ${copied ? 'message-copy-btn--done' : ''}`}
      onClick={() => copy(text ?? '')}
      title={copied ? t('common:copied') : t('message.copyMessage')}
      type="button"
    >
      {copied ? <IconCopied /> : <IconCopySmall />}
    </button>
  );
};

/** Задержка (мс) перед показом индикатора «готовлю данные…» — короткие паузы не мигают. */
const PREPARING_VISIBLE_AFTER_MS = 5000;

/**
 * Индикатор раннего сигнала: модель формирует вызов инструмента (имя ещё недоступно).
 * Показываем под сообщением и только если подготовка тянется дольше 5 секунд —
 * быстрые вызовы проходят незаметно. Таймер живёт внутри компонента, поэтому редьюсер
 * остаётся чистым (без меток времени).
 */
const ToolPreparingIndicator = () => {
  const { t } = useTranslation('chat');
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const id = setTimeout(() => setVisible(true), PREPARING_VISIBLE_AFTER_MS);
    return () => clearTimeout(id);
  }, []);

  if (!visible) return null;

  return (
    <div className="tool-preparing" role="status" aria-live="polite">
      <span className="tool-preparing-dots" aria-hidden="true">
        <span />
        <span />
        <span />
      </span>
      <span className="tool-preparing-text">{t('toolCall.preparing')}</span>
    </div>
  );
};

// ─── Markdown components (стиль KnowledgeBase .md-preview) ─────────────────────
// Вынесено в фабрику, чтобы ссылки получали onNavigateToDoc через замыкание.

function getMarkdownComponents(onNavigateToDoc) {
  return {
    a: ({ href, children, ...props }) => (
      <DocLinkTooltip href={href} onNavigate={onNavigateToDoc} {...props}>
        {children}
      </DocLinkTooltip>
    ),
    code({ inline, className, children, ...props }) {
      const raw = String(children).replace(/\n$/, '');
      const isBlock = !inline && (raw.includes('\n') || /language-(\w+)/.test(className || ''));

      if (!isBlock) {
        return (
          <code className={className} {...props}>
            {children}
          </code>
        );
      }
      return (
        <CodeBlock code={raw} className={className} {...props}>
          {raw}
        </CodeBlock>
      );
    },
  };
}

/**
 * Форматирует timestamp: если < 24ч — относительное время, иначе — дата.
 * Локаль берётся из i18n (lang) — относительное время и плюрализацию даёт нативный
 * Intl.RelativeTimeFormat, поэтому отдельные ключи перевода не нужны.
 */
const formatTimestamp = (ts, lang) => {
  if (!ts) return null;
  const date = new Date(ts);
  if (isNaN(date)) return null;
  const diffMs = Date.now() - date.getTime();
  if (diffMs < 0) return null;
  const diffMin = Math.floor(diffMs / 60000);
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: 'auto' });
  if (diffMin < 1) return rtf.format(0, 'minute');
  if (diffMin < 60) return rtf.format(-diffMin, 'minute');
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return rtf.format(-diffH, 'hour');
  return date.toLocaleDateString(lang, { day: 'numeric', month: 'short' });
};

const formatFullDatetime = (ts, lang) => {
  if (!ts) return null;
  const date = new Date(ts);
  if (isNaN(date)) return null;
  return date.toLocaleString(lang, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const Message = ({
  text,
  sender,
  toolCalls,
  preparing,
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
}) => {
  const { t, i18n } = useTranslation('chat');
  const [showSource, setShowSource] = useState(false);
  const messageClass =
    `message ${sender}` +
    (error && sender === SENDER.AI ? ' message--error' : '') +
    // Отправлено во время ответа и ещё не доставлено в историю: пузырь приглушён, пока
    // прогон не дойдёт до места, где вопрос можно вставить (см. useChatRun.queueMessage).
    (queued ? ' message--queued' : '');
  const hasToolCalls = toolCalls && toolCalls.length > 0;
  const showPreparing = preparing && sender === SENDER.AI;
  const timeLabel = formatTimestamp(timestamp, i18n.language);
  const timeTitle = formatFullDatetime(timestamp, i18n.language);

  // Стабильные идентичности markdown-компонентов между рендерами (как в
  // MarkdownEditor). Без useMemo каждый рендер создаёт новые функции `code`/`a`,
  // React считает их другими типами и пересоздаёт DOM-поддеревья кода и ссылок
  // с новыми текстовыми узлами. Это ломает CSS Highlight подсветку find-бара:
  // её Range-ы держат ссылки на старые узлы, и совпадения в `код`-фрагментах
  // гасли при любом ре-рендере списка (например, setShowScrollButton после
  // плавного скролла к совпадению).
  const mdComponents = useMemo(() => getMarkdownComponents(onNavigateToDoc), [onNavigateToDoc]);

  // Разбивка — в подсказке: в футере на неё нет места, а нужна она редко. Сверху три числа про
  // сам разговор, снизу — оплаченное, которое без строки про кэш выглядит необъяснимо большим.
  const usageTitle = hasUsage(usage)
    ? [
        t('message.tokensContext', { context: formatTokens(usage.contextTokens) }),
        usage.toolTokens > 0 ? t('message.tokensTools', { tools: formatTokens(usage.toolTokens) }) : null,
        t('message.tokensOutput', { output: formatTokens(usage.outputTokens) }),
        t('message.tokensBilled', { billed: formatTokens(billedTokens(usage)), calls: usage.modelCalls }),
        usage.cacheReadTokens > 0
          ? t('message.tokensCached', { cached: formatTokens(usage.cacheReadTokens), percent: cacheShare(usage) })
          : null,
      ]
        .filter(Boolean)
        .join('\n')
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
          <div className="user-message-text">{text}</div>
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
          {timeLabel && (
            <span className="message-footer__time" title={timeTitle ?? undefined}>
              {timeLabel}
            </span>
          )}
          {/* Модель этого ответа — не та, что выбрана в чате сейчас (её показывает вкладка
              «Инфо»): модель переключают посреди чата, и старые ответы остаются за прежней. */}
          {modelLabel && (
            <span className="message-footer__model" title={t('message.answeredBy', { model: modelLabel })}>
              {modelLabel}
            </span>
          )}
          {/* Токены всего прогона, а не этого сегмента: ответ с инструментами — это несколько
              обращений к модели, и плашка стоит на последнем его пузыре. В ней занятый контекст:
              оплаченное больше в разы и в футере читалось бы как счёт за один ответ. */}
          {hasUsage(usage) && (
            <span className="message-footer__tokens" title={usageTitle}>
              {t('message.tokens', { context: formatTokens(usage.contextTokens) })}
            </span>
          )}
        </div>
        <div className="message-footer__actions">
          {error && onRetry && (
            <button className="message-retry-btn" onClick={onRetry} title={t('message.retry')} type="button">
              ↻ {t('message.retry')}
            </button>
          )}
          <MessageCopyButton text={text} />
          <button
            className={`message-source-btn ${showSource ? 'message-source-btn--active' : ''}`}
            onClick={() => setShowSource((v) => !v)}
            title={showSource ? t('message.viewFormatted') : t('message.viewSource')}
          >
            {showSource ? `◈ ${t('message.btnMarkdown')}` : `{ } ${t('message.btnSource')}`}
          </button>
        </div>
      </div>
    ) : (
      <div className="message-footer message-footer--user">
        <MessageCopyButton text={text} />
        {timeLabel && (
          <span className="message-footer__time" title={timeTitle ?? undefined}>
            {timeLabel}
          </span>
        )}
      </div>
    );

  // Сегмент из одних вызовов инструментов (модель не написала текста перед tool_calls):
  // пустой пузырь и футер не рисуем — остаются только плашки вызовов.
  const toolCallsOnly = hasToolCalls && sender === SENDER.AI && !(text || '').trim();

  const messageBlock = (
    <div
      className={`message-block message-block--${sender}${searchActive ? ' message-block--search-hit' : ''}`}
      data-mid={mid ?? undefined}
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
  if (showPreparing) {
    return (
      <>
        {messageBlock}
        <ToolPreparingIndicator />
      </>
    );
  }

  return messageBlock;
};

export default Message;
