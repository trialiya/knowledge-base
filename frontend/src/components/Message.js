import React from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import './Message.css';

// Добавляем пустую строку перед списками, если она отсутствует
const preprocessText = (text) => {
  if (!text) return text;
  // Разбиваем на строки
  const lines = text.split('\n');
  const result = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const prevLine = result.length > 0 ? result[result.length - 1] : null;
    // Если строка начинается с цифры и точки или дефиса/звездочки, а предыдущая не пустая, вставляем пустую строку
    if (/^(\d+\.|\-|\*)\s/.test(line.trim()) && prevLine !== null && prevLine.trim() !== '') {
      result.push('');
    }
    result.push(line);
  }
  return result.join('\n');
};

const Message = ({ text, sender }) => {
  const messageClass = `message ${sender}`;

  return (
    <div className={messageClass}>
      {sender === 'ai' ? (
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            code({ node, inline, className, children, ...props }) {
              const match = /language-(\w+)/.exec(className || '');
              return !inline && match ? (
                <SyntaxHighlighter style={vscDarkPlus} language={match[1]} PreTag="div" {...props}>
                  {String(children).replace(/\n$/, '')}
                </SyntaxHighlighter>
              ) : (
                <code className={className} {...props}>
                  {children}
                </code>
              );
            },
            table({ children }) {
              return <table className="markdown-table">{children}</table>;
            },
          }}
        >
          {/* Применяем предобработку */}
          {preprocessText(text)}
        </ReactMarkdown>
      ) : (
        <div className="user-message-text">{text}</div>
      )}
    </div>
  );
};

export default Message;
