import React, { useState } from 'react';
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

const Message = ({ text, sender }) => {
  const [showSource, setShowSource] = useState(false);
  const messageClass = `message ${sender}`;

  return (
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
                code({ node, inline, className, children, ...props }) {
                  const match = /language-(\w+)/.exec(className || '');
                  if (inline) {
                    return (
                      <code className={className} {...props}>
                        {children}
                      </code>
                    );
                  }
                  // Block code: use highlighter when language is known,
                  // otherwise render a plain <pre> block (NOT inline <code>).
                  return match ? (
                    <SyntaxHighlighter style={vscDarkPlus} language={match[1]} PreTag="div" {...props}>
                      {String(children).replace(/\n$/, '')}
                    </SyntaxHighlighter>
                  ) : (
                    <pre className="message-code-block">
                      <code>{String(children).replace(/\n$/, '')}</code>
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
};

export default Message;
