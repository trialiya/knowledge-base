import { useTranslation } from 'react-i18next';
import { formatCompactDateTime } from '@/utils/formatting';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultSummary from './resultSummary';

// Режим «Обзор» для ответа `getLineOrigin`: строка и запрос, под ними коммит-ответ
// со строкой «стало» и «было», а ниже — весь путь строки, версия за версией.
// Путь не сворачивается: окно вызова и открывают затем, чтобы его прочесть.
//
// Ссылки открываются в новой вкладке, как и остальные ссылки модалки: переход
// внутри приложения увёл бы раздел из-под открытого окна.
//
// Разбор ответа — в originResult.js; сюда приходят уже готовые шаги.

/**
 * Коммит строкой: хеш-ссылка, дата, автор и тема. Шаг на границе неглубокого
 * клона приходит без автора и темы — вместо них подпись: строку тот коммит мог и
 * не писать.
 */
const Commit = ({ step, project }) => {
  const { t, i18n } = useTranslation('chat');
  return (
    <span className="tool-origin__commit">
      <CommitHashLink className="commit-hash-link" rev={step.hash} project={project} newTab>
        {step.shortHash}
      </CommitHashLink>
      <span className="tool-origin__meta">{formatCompactDateTime(step.date, i18n.language)}</span>
      {step.boundary ? (
        <span className="tool-origin__boundary">{t('toolCall.detail.origin.boundaryStep')}</span>
      ) : (
        <>
          {step.author && <span className="tool-origin__meta">{step.author}</span>}
          {step.summary && (
            <span className="tool-origin__summary" title={step.summary}>
              {step.summary}
            </span>
          )}
        </>
      )}
    </span>
  );
};

/** Версия строки: номер в файле того коммита и сам текст, ссылкой на это место. */
const VersionLine = ({ label, step, query }) => (
  <a className="tool-origin__line" href={step.href} target="_blank" rel="noreferrer">
    {label && <span className="tool-origin__line-label">{label}</span>}
    <span className="tool-origin__no">{step.line}</span>
    <code className="tool-origin__code">{highlightSubstring(step.text, query)}</code>
  </a>
);

const OriginResultView = ({ data }) => {
  const { t } = useTranslation('chat');
  const { kind, origin, before, versions, query, project } = data;

  return (
    <div className="tool-origin">
      <ResultSummary expand={null}>
        {t(`toolCall.detail.origin.status.${kind}`)}
        {versions.length > 0 && ` · ${t('toolCall.detail.origin.versions', { count: versions.length })}`}
        {project && ` · ${t('toolCall.detail.fact.project')} ${project}`}
      </ResultSummary>

      <div className="tool-origin__file">
        <a className="tool-origin__path" href={data.href} target="_blank" rel="noreferrer" title={data.path}>
          {data.path}:{data.line}
        </a>
        {data.shortRev && <span className="tool-origin__rev">@{data.shortRev}</span>}
        <code className="tool-origin__query">{query}</code>
      </div>

      {origin && (
        <div className="tool-origin__answer">
          <Commit step={origin} project={project} />
          {kind !== 'found' && <div className="tool-origin__hint">{t(`toolCall.detail.origin.hint.${kind}`)}</div>}
          <VersionLine label={t('toolCall.detail.origin.became')} step={origin} query={query} />
          {before ? (
            <VersionLine label={t('toolCall.detail.origin.was')} step={before} query={query} />
          ) : (
            kind === 'found' && <div className="tool-origin__hint">{t('toolCall.detail.origin.hint.added')}</div>
          )}
        </div>
      )}
      {!origin && before && (
        <div className="tool-origin__answer">
          <VersionLine label={t('toolCall.detail.origin.was')} step={before} query={query} />
        </div>
      )}

      {versions.length > 1 && (
        <ol className="tool-origin__versions">
          {versions.map((step) => (
            <li key={step.hash} className="tool-origin__step">
              <Commit step={step} project={project} />
              <VersionLine step={step} query={query} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
};

export default OriginResultView;
