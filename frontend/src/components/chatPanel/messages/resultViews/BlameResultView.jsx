import { useTranslation } from 'react-i18next';
import { formatCompactDateTime, formatDateTime } from '@/utils/formatting';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import ResultSummary from './resultSummary';

// Режим «Обзор» для ответа `getBlame`: файл и диапазон, под ними строка на
// ханк — номера строк, хеш, автор, дата, описание коммита. Ханки не
// сворачиваются: строка ханка и есть всё, что о нём известно.
//
// Ссылки открываются в новой вкладке, как и остальные ссылки модалки: переход
// внутри приложения увёл бы раздел из-под открытого окна.
//
// Разбор ответа — в blameResult.js; сюда приходят уже готовые строки.

const BlameRow = ({ row, project }) => {
  const { t, i18n } = useTranslation('chat');

  if (row.uncommitted) {
    return (
      <div className="tool-blame__row">
        <span className="tool-blame__lines">{row.lines}</span>
        <span className="tool-blame__uncommitted">{t('toolCall.detail.blame.uncommitted')}</span>
      </div>
    );
  }

  // Подсказка — как у ячейки blame в «Файлах»: описание целиком, под ним автор, хеш и дата.
  const byline = [row.author, row.shortHash, formatDateTime(row.date, i18n.language)].filter(Boolean).join(' · ');
  const title = [row.summary, byline].filter(Boolean).join('\n');
  return (
    <div className="tool-blame__row" title={title}>
      <a className="tool-blame__lines" href={row.href} target="_blank" rel="noreferrer">
        {row.lines}
      </a>
      <CommitHashLink className="commit-hash-link" rev={row.hash} project={project} newTab>
        {row.shortHash}
      </CommitHashLink>
      <span className="tool-blame__author">{row.author}</span>
      <span className="tool-blame__date">{formatCompactDateTime(row.date, i18n.language)}</span>
      <span className="tool-blame__summary">{row.summary}</span>
    </div>
  );
};

const BlameResultView = ({ data }) => {
  const { t } = useTranslation('chat');
  const { range } = data;

  return (
    <div className="tool-blame">
      <ResultSummary expand={null}>
        {t('toolCall.detail.blame.hunks', { count: data.rows.length })}
        {' · '}
        {range
          ? t('toolCall.detail.blame.range', { from: range.from, to: range.to, count: data.lineCount })
          : t('toolCall.detail.blame.lines', { count: data.lineCount })}
        {data.truncated && ` · ${t('toolCall.detail.blame.truncated')}`}
        {data.project && ` · ${t('toolCall.detail.fact.project')} ${data.project}`}
      </ResultSummary>

      <div className="tool-blame__file">
        <a className="tool-blame__path" href={data.href} target="_blank" rel="noreferrer" title={data.path}>
          {data.path}
        </a>
        {data.shortRev && <span className="tool-blame__rev">@{data.shortRev}</span>}
      </div>

      {range?.empty ? (
        <div className="tool-blame__empty">{t('toolCall.detail.blame.empty')}</div>
      ) : (
        <div className="tool-blame__rows">
          {data.rows.map((row) => (
            <BlameRow key={row.key} row={row} project={data.project} />
          ))}
        </div>
      )}
    </div>
  );
};

export default BlameResultView;
