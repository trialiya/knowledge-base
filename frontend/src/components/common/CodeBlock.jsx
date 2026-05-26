import React, { useState, useRef } from 'react';
import './codeBlock.css';

const IconCopy = () => (
  <svg
    width="14"
    height="14"
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

const IconCheck = () => (
  <svg
    width="14"
    height="14"
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

const extractLang = (className) => {
  const m = /language-([\w-]+)/.exec(className || '');
  return m ? m[1] : null;
};

const CodeBlock = ({ code, className, children, ...props }) => {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef(null);
  const lang = extractLang(className);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard API недоступен в insecure context */
    }
  };

  return (
    <div className="code-block">
      <div className="code-block__head">
        <span className="code-block__lang">{lang || ''}</span>
        <button
          className={`code-block__copy ${copied ? 'code-block__copy--done' : ''}`}
          onClick={handleCopy}
          title={copied ? 'Скопировано' : 'Скопировать'}
          type="button"
        >
          {copied ? <IconCheck /> : <IconCopy />}
        </button>
      </div>
      <pre>
        <code className={className} {...props}>
          {children}
        </code>
      </pre>
    </div>
  );
};

export default CodeBlock;
