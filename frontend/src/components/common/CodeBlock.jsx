import React from 'react';
import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '../../icons';
import useCopyFeedback from '../../hooks/useCopyFeedback';
import './codeBlock.css';

const extractLang = (className) => {
  const m = /language-([\w-]+)/.exec(className || '');
  return m ? m[1] : null;
};

const CodeBlock = ({ code, className, children, ...props }) => {
  const { t } = useTranslation();
  const [copied, copy] = useCopyFeedback();
  const lang = extractLang(className);

  return (
    <div className="code-block">
      <div className="code-block__head">
        <span className="code-block__lang">{lang || ''}</span>
        <button
          className={`code-block__copy ${copied ? 'code-block__copy--done' : ''}`}
          onClick={() => copy(code)}
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
