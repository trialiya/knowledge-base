import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import CommitHashLink from '@/components/common/git/CommitHashLink';
import shortRev from '@/components/common/git/shortRev';
import './compareInfo.css';

/**
 * Вкладка «Сравнение» правой панели — о сравнении целиком, а не об открытом
 * файле: какие ревизии сравниваются, где они разошлись, сколько коммитов по
 * каждую сторону и какие именно у показанной. Сами файлы — в режиме
 * «Изменения» слева, как у вкладки «Коммит».
 *
 * Как считать разницу — от общего предка или напрямую — выбирается здесь, а
 * не в тулбаре: вопрос редкий, а объяснить его можно только рядом со сводкой,
 * где виден сам предок.
 *
 * `comparison` — ответ useComparison (GitComparison); `headName` — как назвать
 * сравниваемую сторону: ревизия снимка или текущая ветка рабочего дерева.
 */
const CompareInfo = ({
  base,
  headName,
  project,
  direct,
  comparison,
  loading,
  error,
  onDirectChange,
  onSwap,
  onExit,
}) => {
  const { t } = useTranslation('files');

  if (loading) return <p className="info-list__hint">{t('loading')}</p>;
  if (error || !comparison) return <p className="info-list__hint">{t('compare.loadError')}</p>;

  const files = comparison.files ?? [];
  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);
  const log = comparison.log;
  const more = log ? log.ahead - log.commits.length : 0;

  const commitRef = (name, commit) => (
    <span className="compare-info__rev">
      {name && !commit.hash.startsWith(name) && <span>{name}</span>}
      <CommitHashLink rev={commit.hash} project={project}>
        {commit.shortHash}
      </CommitHashLink>
    </span>
  );

  const rows = [
    { label: t('compare.head'), value: commitRef(headName, comparison.head), copy: comparison.head.hash },
    { label: t('compare.base'), value: commitRef(base, comparison.base), copy: comparison.base.hash },
    {
      label: t('compare.mergeBase'),
      value: comparison.mergeBase ? (
        <CommitHashLink rev={comparison.mergeBase} project={project}>
          {shortRev(comparison.mergeBase)}
        </CommitHashLink>
      ) : (
        t('compare.noMergeBase')
      ),
      copy: comparison.mergeBase ?? undefined,
      mono: !!comparison.mergeBase,
    },
    log && {
      label: t('compare.commits'),
      value: t('compare.aheadBehind', {
        ahead: atLeast(log.ahead, log.aheadTruncated),
        behind: atLeast(log.behind, log.behindTruncated),
      }),
    },
    {
      label: t('commit.files'),
      value: t('commit.filesSummary', { count: files.length, additions, deletions }),
    },
  ];

  // Без общего предка «от предка» не бывает: сервер уже сравнил напрямую, и
  // выбор между двумя одинаковыми ответами только запутал бы. Пояснение
  // следует выбору, а не `diffBase`: когда база сама и есть предок, оба
  // режима сравнивают с одним коммитом, а сказано должно быть то, что выбрали.
  const againstMergeBase = !!comparison.mergeBase && !direct;

  return (
    <>
      <InfoList rows={rows} />
      {comparison.mergeBase && (
        <div className="compare-info__mode" role="group" aria-label={t('compare.mode')}>
          <button
            type="button"
            className="btn btn--ghost btn--xs"
            aria-pressed={!direct}
            title={t('compare.fromMergeBaseHint')}
            onClick={() => onDirectChange(false)}
          >
            {t('compare.fromMergeBase')}
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--xs"
            aria-pressed={direct}
            title={t('compare.directHint')}
            onClick={() => onDirectChange(true)}
          >
            {t('compare.direct')}
          </button>
        </div>
      )}
      <p className="info-list__hint">
        {againstMergeBase ? t('compare.explainMergeBase', { base }) : t('compare.explainDirect', { base })}
      </p>
      {/* Сравниваемая ревизия — предок базы: от развилки в ней не изменилось
          ничего, и пустой список без этой строки читался бы как «ревизии
          одинаковы». */}
      {againstMergeBase && files.length === 0 && log?.behind > 0 && (
        <p className="info-list__hint">{t('compare.onlyBehind', { base })}</p>
      )}

      {log && log.commits.length > 0 && (
        <section className="compare-info__log" aria-label={t('compare.commitsTitle')}>
          <p className="compare-info__log-title">{t('compare.commitsTitle')}</p>
          <ul className="compare-info__commits">
            {log.commits.map((commit) => (
              <li key={commit.hash} className="compare-info__commit">
                <CommitHashLink rev={commit.hash} project={project} className="compare-info__hash">
                  {commit.shortHash}
                </CommitHashLink>
                <span className="compare-info__message" title={commit.message}>
                  {commit.message}
                </span>
              </li>
            ))}
          </ul>
          {more > 0 && <p className="info-list__hint">{t('compare.moreCommits', { count: more })}</p>}
        </section>
      )}

      <div className="commit-info__actions">
        <button type="button" className="btn btn--ghost btn--sm" onClick={onSwap}>
          {t('compare.swap')}
        </button>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onExit}>
          {t('compare.exit')}
        </button>
      </div>
    </>
  );
};

/** Счёт, остановленный на пределе, — нижняя граница, и пишется так. */
const atLeast = (count, truncated) => `${count}${truncated ? '+' : ''}`;

export default CompareInfo;
