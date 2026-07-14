import React, { useState, useRef, useEffect, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useTranslation } from 'react-i18next';
import DocLinkTooltip from '../common/DocLinkTooltip';
import './message.css';
import CodeBlock from '../common/CodeBlock';
import ToolCallNotifications from './ToolCallNotifications';
import DocChangeBlock from './DocChangeBlock';
import { IconCopySmall, IconCopied } from '../../icons';
import { COPY_DONE_MS } from '../../constants/ui';

/** Кнопка «копировать всё сообщение» — копирует исходный текст сообщения. */
const MessageCopyButton = ({ text }) => {
  const { t } = useTranslation('chat');
  const [copied, setCopied] = useState(false);
  const timerRef = useRef(null);

  useEffect(() => () => clearTimeout(timerRef.current), []);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text ?? '');
      setCopied(true);
      clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => setCopied(false), COPY_DONE_MS);
    } catch {
      /* clipboard API may fail in insecure contexts */
    }
  };

  return (
    <button
      className={`message-copy-btn ${copied ? 'message-copy-btn--done' : ''}`}
      onClick={handleCopy}
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
  toolCallsRunId,
  preparing,
  error,
  onRetry,
  conversationId,
  onNavigateToDoc,
  timestamp,
  mid,
  searchActive,
}) => {
  const { t, i18n } = useTranslation('chat');
  const [showSource, setShowSource] = useState(false);
  const messageClass = `message ${sender}${error && sender === 'ai' ? ' message--error' : ''}`;
  const hasToolCalls = toolCalls && toolCalls.length > 0;
  const showPreparing = preparing && sender === 'ai';
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

  // Пузырь — только контент сообщения, без футера
  const bubble = (
    <div className={messageClass}>
      {sender === 'ai' ? (
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
        <div className="user-message-text">{text}</div>
      )}
    </div>
  );

  // Футер под пузырём: AI — время слева, кнопки справа;
  // user — кнопка слева, время справа.
  const footer =
    sender === 'ai' ? (
      <div className="message-footer message-footer--ai">
        {timeLabel && (
          <span className="message-footer-time" title={timeTitle ?? undefined}>
            {timeLabel}
          </span>
        )}
        <div className="message-footer-actions">
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
          <span className="message-footer-time" title={timeTitle ?? undefined}>
            {timeLabel}
          </span>
        )}
      </div>
    );

  const messageBlock = (
    <div
      className={`message-block message-block--${sender}${searchActive ? ' message-block--search-hit' : ''}`}
      data-mid={mid ?? undefined}
    >
      {bubble}
      {footer}
    </div>
  );

  if (hasToolCalls && sender === 'ai') {
    return (
      <div className="message-row-with-tools">
        <div className="message-main-col">
          {messageBlock}
          <DocChangeBlock toolCalls={toolCalls} onNavigateToDoc={onNavigateToDoc} />
          {showPreparing && <ToolPreparingIndicator />}
        </div>
        <ToolCallNotifications toolCalls={toolCalls} conversationId={conversationId} toolCallsRunId={toolCallsRunId} />
      </div>
    );
  }

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
