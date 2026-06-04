import React, { useState, useRef, useEffect } from 'react';
import ReactDOM from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useTranslation } from 'react-i18next';
import ChatDocLink from './ChatDocLink';
import './message.css';
import CodeBlock from '../common/CodeBlock';
import { getToolIcon, toolLabelKey, humanizeTool } from './toolMeta';

/** SVG status indicators — not clickable, purely visual */
const IconStarted = () => (
  <svg className="tool-call-status-svg tool-call-status-svg--started" width="14" height="14" viewBox="0 0 16 16">
    <circle cx="8" cy="8" r="6" fill="none" stroke="#d99a00" strokeWidth="2" strokeDasharray="9 5" />
  </svg>
);
const IconOk = () => (
  <svg className="tool-call-status-svg tool-call-status-svg--ok" width="14" height="14" viewBox="0 0 16 16">
    <circle cx="8" cy="8" r="7" fill="#34a853" />
    <path
      d="M5 8.2l2 2 4-4.4"
      fill="none"
      stroke="#fff"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);
const IconError = () => (
  <svg className="tool-call-status-svg tool-call-status-svg--error" width="14" height="14" viewBox="0 0 16 16">
    <circle cx="8" cy="8" r="7" fill="#ea4335" />
    <path d="M5.5 5.5l5 5M10.5 5.5l-5 5" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
);

const StatusIcon = ({ status }) => {
  switch (status) {
    case 'STARTED':
      return <IconStarted />;
    case 'OK':
      return <IconOk />;
    case 'ERROR':
      return <IconError />;
    default:
      return <IconStarted />;
  }
};

/** Small copy button SVG */
const IconCopy = () => (
  <svg
    width="12"
    height="12"
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.6"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
    <path d="M10.5 5.5V3.5a1.5 1.5 0 0 0-1.5-1.5H3.5A1.5 1.5 0 0 0 2 3.5V9a1.5 1.5 0 0 0 1.5 1.5h2" />
  </svg>
);
const IconCopied = () => (
  <svg
    width="12"
    height="12"
    viewBox="0 0 16 16"
    fill="none"
    stroke="#34a853"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M3 8.5l3 3 7-7.5" />
  </svg>
);

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
      timerRef.current = setTimeout(() => setCopied(false), 1500);
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
      {copied ? <IconCopied /> : <IconCopy />}
    </button>
  );
};

const formatArgs = (args) => {
  if (!args || Object.keys(args).length === 0) return null;
  return Object.entries(args)
    .map(([key, val]) => `${key}: ${typeof val === 'string' ? val : JSON.stringify(val)}`)
    .join(', ');
};

const GIST_PREVIEW_LEN = 80;

/** Однострочное усечённое превью resultGist для плашки. */
const gistPreview = (gist) => {
  if (!gist) return null;
  const oneLine = gist.replace(/\s+/g, ' ').trim();
  return oneLine.length > GIST_PREVIEW_LEN ? oneLine.slice(0, GIST_PREVIEW_LEN) + '…' : oneLine;
};

/**
 * Build a copyable text summary of a tool call.
 * `t` передаётся параметром, т.к. функция вне области React-хука.
 */
const buildCopyText = (tc, t) => {
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const parts = [label];
  const argsStr = formatArgs(tc.arguments);
  if (argsStr) parts.push(argsStr);
  if (tc.resultGist) parts.push(`${t('toolCall.result')}: ${tc.resultGist}`);
  parts.push(`${t('toolCall.status')}: ${tc.status || '—'}`);
  if (tc.status === 'ERROR' && tc.error) parts.push(`${t('toolCall.error')}: ${tc.error}`);
  return parts.join('\n');
};

/** Одиночная плашка вызова — hover-тултип + кнопка копирования */
const ToolCallItem = ({ tc }) => {
  const { t } = useTranslation('chat');
  const label = t(toolLabelKey(tc.name), { defaultValue: humanizeTool(tc.name) });
  const icon = getToolIcon(tc.name);
  const argsStr = formatArgs(tc.arguments);
  const gist = gistPreview(tc.resultGist);
  const itemRef = useRef(null);
  const [hover, setHover] = useState(false);
  // placement: 'left' | 'right' — на какой стороне плашки рисуем тултип
  const [tooltipPos, setTooltipPos] = useState({ top: 0, left: 0, placement: 'left' });
  const [copied, setCopied] = useState(false);

  const TOOLTIP_WIDTH = 320; // должен совпадать с max-width в CSS
  const GAP = 8;

  const handleMouseEnter = () => {
    if (itemRef.current) {
      const rect = itemRef.current.getBoundingClientRect();
      const vw = window.innerWidth;
      const vh = window.innerHeight;

      // Сколько места справа и слева от плашки
      const spaceRight = vw - rect.right;
      const spaceLeft = rect.left;

      let placement;
      let left;

      if (spaceRight >= TOOLTIP_WIDTH + GAP) {
        // помещается справа — как раньше
        placement = 'right';
        left = rect.right + GAP;
      } else if (spaceLeft >= TOOLTIP_WIDTH + GAP) {
        // не помещается справа, но помещается слева — рисуем слева
        placement = 'left';
        left = rect.left - GAP - TOOLTIP_WIDTH;
      } else {
        // тесно с обеих сторон — прижимаем к правому краю экрана с отступом
        placement = 'right';
        left = Math.max(GAP, vw - TOOLTIP_WIDTH - GAP);
      }

      // Вертикально: не даём уйти за нижнюю кромку экрана.
      let top = rect.top;
      const approxHeight = 160;
      if (top + approxHeight > vh) {
        top = Math.max(GAP, vh - approxHeight - GAP);
      }

      setTooltipPos({ top, left, placement });
    }
    setHover(true);
  };

  const handleCopy = async (e) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(buildCopyText(tc, t));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard API may fail in insecure contexts */
    }
  };

  return (
    <div
      ref={itemRef}
      className={`tool-call-item tool-call-item--${(tc.status || 'STARTED').toLowerCase()}`}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={() => setHover(false)}
    >
      <span className="tool-call-status-icon">
        <StatusIcon status={tc.status} />
      </span>
      <div className="tool-call-body">
        <span className="tool-call-name">
          <span className="tool-call-icon" aria-hidden="true">
            {icon}
          </span>
          {label}
        </span>
        {argsStr && <span className="tool-call-args">{argsStr}</span>}
        {gist && <span className="tool-call-gist">{gist}</span>}
        {tc.status === 'ERROR' && tc.error && <span className="tool-call-error">{tc.error}</span>}
      </div>
      <button className="tool-call-copy-btn" onClick={handleCopy} title={t('toolCall.copy')}>
        {copied ? <IconCopied /> : <IconCopy />}
      </button>
      {hover &&
        ReactDOM.createPortal(
          <div
            className={`tool-call-tooltip tool-call-tooltip--${tooltipPos.placement}`}
            style={{ top: tooltipPos.top, left: tooltipPos.left }}
          >
            <div className="tool-call-tooltip-name">{label}</div>
            {argsStr && <div className="tool-call-tooltip-args">{argsStr}</div>}
            <div className="tool-call-tooltip-status">
              {t('toolCall.status')}: {tc.status || '—'}
            </div>
            {tc.resultGist && <div className="tool-call-tooltip-gist">{tc.resultGist}</div>}
            {tc.status === 'ERROR' && tc.error && <div className="tool-call-tooltip-error">{tc.error}</div>}
          </div>,
          document.body,
        )}
    </div>
  );
};

/** Группа одноимённых последовательных вызовов — сворачиваемая */
const ToolCallGroup = ({ name, items }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState(false);

  // Одиночный вызов — рендерим как обычную плашку, без шеврона/бейджа
  if (items.length === 1) {
    return <ToolCallItem tc={items[0]} />;
  }

  // Группа ≥2: заголовок показывает аргументы первого вызова (чтобы высота
  // не прыгала при переходе 1→2), плюс бейдж ×N и шеврон.
  const first = items[0];
  const label = t(toolLabelKey(name), { defaultValue: humanizeTool(name) });
  const icon = getToolIcon(name);
  const firstArgsStr = formatArgs(first.arguments);
  const groupStatus = items.some((t2) => t2.status === 'ERROR')
    ? 'ERROR'
    : items.some((t2) => t2.status === 'STARTED')
    ? 'STARTED'
    : 'OK';

  return (
    <div className="tool-call-group">
      <div
        className={`tool-call-item tool-call-item--${groupStatus.toLowerCase()} tool-call-item--group-header`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="tool-call-status-icon">
          <StatusIcon status={groupStatus} />
        </span>
        <div className="tool-call-body">
          <span className="tool-call-name">
            <span className="tool-call-icon" aria-hidden="true">
              {icon}
            </span>
            {label}
            <span className="tool-call-count">×{items.length}</span>
          </span>
          {firstArgsStr && <span className="tool-call-args">{firstArgsStr}</span>}
        </div>
        <span className={`tool-call-chevron ${open ? 'tool-call-chevron--open' : ''}`}>›</span>
      </div>
      {open && (
        <div className="tool-call-group-children">
          {items.map((tc, i) => (
            <ToolCallItem key={i} tc={tc} />
          ))}
        </div>
      )}
    </div>
  );
};

const ToolCallNotifications = ({ toolCalls }) => {
  if (!toolCalls || toolCalls.length === 0) return null;

  // Группируем последовательные вызовы с одним именем
  const groups = [];
  for (const tc of toolCalls) {
    const last = groups[groups.length - 1];
    if (last && last.name === tc.name) {
      last.items.push(tc);
    } else {
      groups.push({ name: tc.name, items: [tc] });
    }
  }

  return (
    <div className="tool-call-notifications">
      <div className="tool-call-scroll">
        {groups.map((g, i) => (
          <ToolCallGroup key={`${g.name}-${i}`} name={g.name} items={g.items} />
        ))}
      </div>
    </div>
  );
};

// ─── Markdown components (стиль KnowledgeBase .md-preview) ─────────────────────
// Вынесено в фабрику, чтобы ссылки получали onNavigateToDoc через замыкание.

function getMarkdownComponents(onNavigateToDoc) {
  return {
    a: ({ href, children, ...props }) => (
      <ChatDocLink href={href} onNavigateToDoc={onNavigateToDoc} {...props}>
        {children}
      </ChatDocLink>
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

const Message = ({ text, sender, toolCalls, onNavigateToDoc }) => {
  const { t } = useTranslation('chat');
  const [showSource, setShowSource] = useState(false);
  const messageClass = `message ${sender}`;
  const hasToolCalls = toolCalls && toolCalls.length > 0;

  const messageContent = (
    <div className={messageClass}>
      {sender === 'ai' ? (
        <>
          <div className="message-source-toggle">
            <MessageCopyButton text={text} />
            <button
              className={`message-source-btn ${showSource ? 'message-source-btn--active' : ''}`}
              onClick={() => setShowSource((v) => !v)}
              title={showSource ? t('message.viewFormatted') : t('message.viewSource')}
            >
              {showSource ? `◈ ${t('message.btnMarkdown')}` : `{ } ${t('message.btnSource')}`}
            </button>
          </div>
          {showSource ? (
            <pre className="message-raw-source">{text}</pre>
          ) : (
            <div className="md-preview md-preview--chat">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={getMarkdownComponents(onNavigateToDoc)}>
                {text}
              </ReactMarkdown>
            </div>
          )}
        </>
      ) : (
        <>
          <div className="message-toolbar message-toolbar--user">
            <MessageCopyButton text={text} />
          </div>
          <div className="user-message-text">{text}</div>
        </>
      )}
    </div>
  );

  if (hasToolCalls && sender === 'ai') {
    return (
      <div className="message-row-with-tools">
        {messageContent}
        <ToolCallNotifications toolCalls={toolCalls} />
      </div>
    );
  }

  return messageContent;
};

export default Message;
