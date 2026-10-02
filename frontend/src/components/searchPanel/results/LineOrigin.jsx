import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import AppLink from '@/components/common/ui/AppLink';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import shortRev from '@/components/common/git/shortRev';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import { FILE_TAB } from '@/constants/fileTabs';
import { filesUrl } from '@/navigation/urlScheme';
import { formatCompactDateTime } from '@/utils/formatting';
import { describeOrigin } from './lineOrigin';
import useLineOrigin from './useLineOrigin';

/**
 * Ответ «когда появилось» под строкой совпадения: коммит, где подстрока вошла в
 * эту строку, строка до него и — свёрнутым — путь строки: каждая её версия,
 * пока подстрока в ней жила.
 *
 * Строка версии ведёт к файлу в снимке того коммита с выделенной строкой и
 * вкладкой «Коммит» — как ячейка blame в «Файлах»: номер и путь там свои, после
 * переименования и правок выше они не совпадают с нынешними.
 */
const LineOrigin = ({ path, line, query, rev, project, onOpenFile }) => {
  const { t } = useTranslation('search');
  const { loading, value, error } = useLineOrigin({ path, line, query, rev, project });
  const [showPath, setShowPath] = useState(false);

  if (loading) return <div className="search-origin search-origin--note">{t('files.origin.loading')}</div>;
  if (error) {
    const reason = error.status === 503 ? 'timeout' : 'failed';
    return <div className="search-origin search-origin--note">{t(`files.origin.${reason}`)}</div>;
  }

  const view = describeOrigin(value);
  if (!view.origin) return <div className="search-origin search-origin--note">{t(`files.origin.${view.kind}`)}</div>;

  const open = (step) => {
    const options = { rev: step.hash, lines: String(step.line), find: query, right: FILE_TAB.COMMIT };
    return {
      href: filesUrl(step.path, project, options),
      onNavigate: () => onOpenFile(step.path, project, options),
    };
  };

  return (
    <div className="search-origin">
      <div className="search-origin__head">
        <span className="search-origin__label">{t(`files.origin.${view.kind}`)}</span>
        <Commit step={view.origin} project={project} />
      </div>
      {view.kind === 'boundary' && <div className="search-origin__hint">{t('files.origin.boundaryHint')}</div>}
      <VersionLine label={t('files.origin.became')} step={view.origin} query={query} link={open(view.origin)} />
      {view.before ? (
        <VersionLine label={t('files.origin.was')} step={view.before} query={query} link={open(view.before)} />
      ) : (
        view.kind === 'found' && <div className="search-origin__hint">{t('files.origin.added')}</div>
      )}
      {view.path.length > 1 && (
        <>
          <button
            type="button"
            className="search-origin__toggle"
            aria-expanded={showPath}
            onClick={() => setShowPath(!showPath)}
          >
            {t('files.origin.path', { count: view.path.length })}
          </button>
          {showPath && (
            <ol className="search-origin__path">
              {view.path.map((step) => (
                <li key={step.hash} className="search-origin__step">
                  <Commit step={step} project={project} />
                  <VersionLine step={step} query={query} link={open(step)} />
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </div>
  );
};

/** Коммит строкой: хеш-ссылка, дата, автор и тема. */
const Commit = ({ step, project }) => {
  const { i18n } = useTranslation('search');
  return (
    <span className="search-origin__commit">
      <CommitHashLink className="commit-hash-link" rev={step.hash} project={project}>
        {shortRev(step.hash)}
      </CommitHashLink>
      <span className="search-origin__meta">{formatCompactDateTime(step.date, i18n.language)}</span>
      {step.author && <span className="search-origin__meta">{step.author}</span>}
      {step.summary && (
        <span className="search-origin__summary" title={step.summary}>
          {step.summary}
        </span>
      )}
    </span>
  );
};

/** Версия строки: номер в файле того коммита и сам текст, ссылкой на это место. */
const VersionLine = ({ label, step, query, link }) => (
  <AppLink className="search-origin__line" href={link.href} onNavigate={link.onNavigate}>
    {label && <span className="search-origin__line-label">{label}</span>}
    <span className="search-line__no">{step.line}</span>
    <code className="search-line__code">{highlightSubstring(step.text, query)}</code>
  </AppLink>
);

export default LineOrigin;
