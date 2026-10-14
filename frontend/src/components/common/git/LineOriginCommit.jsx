import { useTranslation } from 'react-i18next';
import { formatCompactDateTime } from '@/utils/formatting';
import CommitHashLink from './CommitHashLink';
import shortRev from './shortRev';
import './lineOriginCommit.css';

/**
 * Коммит одной версии строки из ответа «когда появилось» (GitLineOrigin): хеш-ссылка,
 * дата, автор и тема. Общий для панели под строкой поиска и вида `origin` в чате.
 *
 * Шаг на границе неглубокого клона (`step.boundary`) приходит без автора и темы — на их
 * месте подпись: git отдаёт такому коммиту всё, что старше клона, и строку он мог не
 * писать.
 *
 * @param newTab хеш открывается в новой вкладке — так ведут себя ссылки модалки вызова
 */
const LineOriginCommit = ({ step, project, newTab = false }) => {
  const { t, i18n } = useTranslation('common');
  return (
    <span className="origin-commit">
      <CommitHashLink className="commit-hash-link" rev={step.hash} project={project} newTab={newTab}>
        {shortRev(step.hash)}
      </CommitHashLink>
      <span className="origin-commit__meta">{formatCompactDateTime(step.date, i18n.language)}</span>
      {step.boundary ? (
        <span className="origin-commit__boundary" title={t('lineOrigin.boundaryTitle')}>
          {t('lineOrigin.boundary')}
        </span>
      ) : (
        <>
          {step.author && <span className="origin-commit__meta">{step.author}</span>}
          {step.summary && (
            <span className="origin-commit__summary" title={step.summary}>
              {step.summary}
            </span>
          )}
        </>
      )}
    </span>
  );
};

export default LineOriginCommit;
