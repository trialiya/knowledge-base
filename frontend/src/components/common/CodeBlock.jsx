import React, { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '../../icons';
import './codeBlock.css';

const extractLang = (className) => {
  const m = /language-([\w-]+)/.exec(className || '');
  return m ? m[1] : null;
};

const CodeBlock = ({ code, className, children, ...props }) => {
  const { t } = useTranslation();
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
          title={copied ? t('copied') : t('copy')}
          type="button"
        >
          {copied ? <IconCopied size={14} /> : <IconCopySmall size={14} />}
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
