import React, { useState, useRef } from 'react';
import ReactDOM from 'react-dom';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import './Message.css';

// Добавляем пустую строку перед списками, если она отсутствует
const preprocessText = (text) => {
  if (!text) return text;
  const lines = text.split('\n');
  const result = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const prevLine = result.length > 0 ? result[result.length - 1] : null;
    if (/^(\d+\.|-|\*)\s/.test(line.trim()) && prevLine !== null && prevLine.trim() !== '') {
      result.push('');
    }
    result.push(line);
  }
  return result.join('\n');
};

const statusIcon = (status) => {
  switch (status) {
    case 'STARTED': return '⏳';
    case 'OK': return '✅';
    case 'ERROR': return '❌';
    default: return '⚙️';
  }
};

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

/** Одиночная плашка вызова — hover-тултип + клик копирует детали */
const ToolCallItem = ({ tc }) => {
  const argsStr = formatArgs(tc.arguments);
  const itemRef = useRef(null);
  const [hover, setHover] = useState(false);
  const [tooltipPos, setTooltipPos] = useState({ top: 0, left: 0 });
  const [copied, setCopied] = useState(false);

  const handleMouseEnter = () => {
    if (itemRef.current) {
      const rect = itemRef.current.getBoundingClientRect();
      setTooltipPos({ top: rect.top, left: rect.right + 8 });
    }
    setHover(true);
  };

  const handleClick = async () => {
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
          onClick={handleClick}
      >
        <span className="tool-call-icon">{statusIcon(tc.status)}</span>
        <div className="tool-call-body">
          <span className="tool-call-name">{tc.name}</span>
          {argsStr && <span className="tool-call-args">{argsStr}</span>}
          {tc.status === 'ERROR' && tc.error && (
              <span className="tool-call-error">{tc.error}</span>
          )}
        </div>
        {copied && <span className="tool-call-copied">✓</span>}
        {hover && !copied && ReactDOM.createPortal(
            <div
                className="tool-call-tooltip"
                style={{ top: tooltipPos.top, left: tooltipPos.left }}
            >
              <div className="tool-call-tooltip-name">{tc.name}</div>
              {argsStr && <div className="tool-call-tooltip-args">{argsStr}</div>}
              <div className="tool-call-tooltip-status">Статус: {tc.status || '—'}</div>
              {tc.status === 'ERROR' && tc.error && (
                  <div className="tool-call-tooltip-error">{tc.error}</div>
              )}
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
          <span className="tool-call-icon">{statusIcon(groupStatus)}</span>
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

const Message = ({ text, sender, toolCalls }) => {
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
                   <ReactMarkdown
                       remarkPlugins={[remarkGfm]}
                       components={{
                         code({ node, className, children, ...props }) {
                           const match = /language-(\w+)/.exec(className || '');
                           const raw = String(children).replace(/\n$/, '');
                           const isBlock = !!match || raw.includes('\n');

                           if (!isBlock) {
                             return (
                                 <code className={className} {...props}>
                                   {children}
                                 </code>
                             );
                           }
                           return match ? (
                               <SyntaxHighlighter style={vscDarkPlus} language={match[1]} PreTag="div" {...props}>
                                 {raw}
                               </SyntaxHighlighter>
                           ) : (
                                      <pre className="message-code-block">
                      <code>{raw}</code>
                    </pre>
                                  );
                         },
                         table({ children }) {
                           return <table className="markdown-table">{children}</table>;
                         },
                       }}
                   >
                     {preprocessText(text)}
                   </ReactMarkdown>
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