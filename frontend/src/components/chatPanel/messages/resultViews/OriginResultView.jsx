import { useTranslation } from 'react-i18next';
import LineOriginCommit from '@/components/common/git/LineOriginCommit';
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
          <LineOriginCommit step={origin} project={project} newTab />
          {kind !== 'found' && <div className="tool-origin__hint">{t(`toolCall.detail.origin.hint.${kind}`)}</div>}
          {/* «Стало» — только у ответа: при BOUNDARY и LIMIT это самая старая пройденная версия. */}
          <VersionLine
            label={kind === 'found' ? t('toolCall.detail.origin.became') : null}
            step={origin}
            query={query}
          />
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
              <LineOriginCommit step={step} project={project} newTab />
              <VersionLine step={step} query={query} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
};

export default OriginResultView;
