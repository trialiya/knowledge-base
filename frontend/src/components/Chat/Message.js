import React, { useState, useRef } from 'react';
import ReactDOM from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import ChatDocLink from './ChatDocLink';
import './Message.css';
import CodeBlock from '../common/CodeBlock';

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

const formatArgs = (args) => {
  if (!args || Object.keys(args).length === 0) return null;
  return Object.entries(args)
    .map(([key, val]) => `${key}: ${typeof val === 'string' ? val : JSON.stringify(val)}`)
    .join(', ');
};

/** Build a copyable text summary of a tool call */
const buildCopyText = (tc) => {
  const parts = [tc.name];
  const argsStr = formatArgs(tc.arguments);
  if (argsStr) parts.push(argsStr);
  parts.push(`Статус: ${tc.status || '—'}`);
  if (tc.status === 'ERROR' && tc.error) parts.push(`Ошибка: ${tc.error}`);
  return parts.join('\n');
};

/** Одиночная плашка вызова — hover-тултип + кнопка копирования */
/** Одиночная плашка вызова — hover-тултип + кнопка копирования */
const ToolCallItem = ({ tc }) => {
  const argsStr = formatArgs(tc.arguments);
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

      // Вертикально: не даём уйти за нижнюю кромку экрана
      let top = rect.top;
      const approxHeight = 120; // запас на высоту тултипа
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
      await navigator.clipboard.writeText(buildCopyText(tc));
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
        <span className="tool-call-name">{tc.name}</span>
        {argsStr && <span className="tool-call-args">{argsStr}</span>}
        {tc.status === 'ERROR' && tc.error && <span className="tool-call-error">{tc.error}</span>}
      </div>
      <button className="tool-call-copy-btn" onClick={handleCopy} title="Скопировать">
        {copied ? <IconCopied /> : <IconCopy />}
      </button>
      {hover &&
        ReactDOM.createPortal(
          <div
            className={`tool-call-tooltip tool-call-tooltip--${tooltipPos.placement}`}
            style={{ top: tooltipPos.top, left: tooltipPos.left }}
          >
            <div className="tool-call-tooltip-name">{tc.name}</div>
            {argsStr && <div className="tool-call-tooltip-args">{argsStr}</div>}
            <div className="tool-call-tooltip-status">Статус: {tc.status || '—'}</div>
            {tc.status === 'ERROR' && tc.error && <div className="tool-call-tooltip-error">{tc.error}</div>}
          </div>,
          document.body,
        )}
    </div>
  );
};

/** Группа одноимённых последовательных вызовов — сворачиваемая */
const ToolCallGroup = ({ name, items }) => {
  const [open, setOpen] = useState(false);

  // Одиночный вызов — рендерим как обычную плашку, без шеврона/бейджа
  if (items.length === 1) {
    return <ToolCallItem tc={items[0]} />;
  }

  // Группа ≥2: заголовок показывает аргументы первого вызова (чтобы высота
  // не прыгала при переходе 1→2), плюс бейдж ×N и шеврон.
  const first = items[0];
  const firstArgsStr = formatArgs(first.arguments);
  const groupStatus = items.some((t) => t.status === 'ERROR')
    ? 'ERROR'
    : items.some((t) => t.status === 'STARTED')
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
            {name}
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
  const [showSource, setShowSource] = useState(false);
  const messageClass = `message ${sender}`;
  const hasToolCalls = toolCalls && toolCalls.length > 0;

  const messageContent = (
    <div className={messageClass}>
      {sender === 'ai' ? (
        <>
          <div className="message-source-toggle">
            <button
              className={`message-source-btn ${showSource ? 'message-source-btn--active' : ''}`}
              onClick={() => setShowSource((v) => !v)}
              title={showSource ? 'Показать с форматированием' : 'Показать исходник'}
            >
              {showSource ? '◈ Markdown' : '{ } Исходник'}
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
        <div className="user-message-text">{text}</div>
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
