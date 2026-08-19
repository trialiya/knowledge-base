import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '@/icons/index';
import ResultSummary, { useExpandAll } from './resultSummary';

// Режим «Обзор» для совпадений `grepContent`: файл → блоки строк с настоящими
// номерами, совпадения подсвечены, контекст приглушён.
//
// Разбор ответа — в grepMatches.js; сюда приходят уже разобранные строки.

const GrepFile = ({ file, open, onToggle }) => {
  const { t } = useTranslation('chat');

  return (
    <div className="tool-grep__file">
      <button type="button" className="tool-grep__head" onClick={onToggle} aria-expanded={open}>
        <span className={`tool-grep__chevron${open ? ' tool-grep__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        <span className="tool-grep__path" title={file.path}>
          {file.path}
        </span>
        <span className="tool-grep__count">{t('toolCall.detail.grep.matches', { count: file.blocks.length })}</span>
      </button>

      {open &&
        file.blocks.map((block) => (
          <div key={block.key} className="tool-grep__block">
            {block.lines.map((line) => (
              <div
                key={`${line.no}-${line.text}`}
                className={`tool-grep__line${line.match ? ' tool-grep__line--match' : ''}`}
              >
                <span className="tool-grep__line-no">{line.no}</span>
                <span className="tool-grep__line-text">{line.text || ' '}</span>
              </div>
            ))}
          </div>
        ))}
    </div>
  );
};

const GrepMatchesView = ({ data }) => {
  const { t } = useTranslation('chat');
  const keys = data.files.map((file) => file.key);
  // Файлы раскрыты сразу: у поиска смысл в самих строках, а не в списке путей.
  const expand = useExpandAll(keys, () => Object.fromEntries(keys.map((key) => [key, true])));

  return (
    <div className="tool-grep">
      <ResultSummary expand={keys.length > 1 ? expand : null}>
        {t('toolCall.detail.grep.matches', { count: data.matches })}
        {' · '}
        {t('toolCall.detail.grep.files', { count: data.files.length })}
        {data.project && ` · ${t('toolCall.detail.fact.project')} ${data.project}`}
      </ResultSummary>

      {data.files.map((file) => (
        <GrepFile key={file.key} file={file} open={expand.isOpen(file.key)} onToggle={() => expand.toggle(file.key)} />
      ))}
    </div>
  );
};

export default GrepMatchesView;
