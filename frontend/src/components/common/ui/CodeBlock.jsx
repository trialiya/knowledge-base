import CopyButton from './CopyButton';
import './codeBlock.css';

const extractLang = (className) => {
  const m = /language-([\w-]+)/.exec(className || '');
  return m ? m[1] : null;
};

const CodeBlock = ({ code, className, children, ...props }) => {
  const lang = extractLang(className);

  return (
    <div className="code-block">
      <div className="code-block__head">
        <span className="code-block__lang">{lang || ''}</span>
        <CopyButton value={code} keepEmpty className="icon-btn--sm code-block__copy" />
      </div>
      <pre className="code-block__pre">
        <code className={className} {...props}>
          {children}
        </code>
      </pre>
    </div>
  );
};

export default CodeBlock;
