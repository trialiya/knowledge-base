import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import gitApi from '@/api/gitApi';
import useListNavigation from '@/components/common/search/useListNavigation';
import { IconX } from '@/icons/index';
import CommitPlate from './CommitPlate';
import CommitDetailModal from './CommitDetailModal';
import useCommitHistory from './useCommitHistory';
import { dayLabel, groupByDay } from './historyDays';

/**
 * Левый блок в режиме «История»: лента коммитов по дням, плашки которой
 * раскрываются на месте — изменённые файлы коммита спрашиваются только при
 * раскрытии, по одному запросу на коммит. Клик по файлу открывает в центре его
 * изменение в этом коммите, а лента остаётся на месте.
 *
 * Ответы про файлы коммита не сбрасываются вместе с лентой: коммит по хешу
 * неизменен, и раскрытая после «Показать ещё» или fetch плашка не обязана
 * спрашивать то же второй раз.
 *
 * `path` — открытый путь: им ленту можно сузить до коммитов, которые его меняли.
 * Сужение — своё состояние, а не сам путь: иначе клик по файлу коммита
 * перестраивал бы ленту, из которой по нему кликнули.
 */
const CommitHistory = ({ project, rev, path, commit, refreshToken, refsToken, onOpenFile, onOpenSnapshot }) => {
  const { t, i18n } = useTranslation('files');
  const handleKeyDown = useListNavigation();
  const [scope, setScope] = useState('');
  const history = useCommitHistory({
    project,
    rev,
    scope,
    refreshToken,
    refsToken,
  });
  const [open, setOpen] = useState(() => new Set());
  // hash → GitDiffEntry[] | null (запрос не удался) | undefined (в пути); нет ключа — не спрашивали.
  const [files, setFiles] = useState({});
  const [detail, setDetail] = useState(null);

  // Ключ ставится сразу, до ответа (`undefined` — в пути): иначе плашка, свёрнутая
  // и раскрытая, пока файлы ещё грузятся, спросила бы их второй раз.
  const loadFiles = (hash) => {
    setFiles((prev) => ({ ...prev, [hash]: undefined }));
    return gitApi
      .getCommit(hash, { project })
      .then((answer) => setFiles((prev) => ({ ...prev, [hash]: answer.files ?? [] })))
      .catch(() => setFiles((prev) => ({ ...prev, [hash]: null })));
  };

  const toggle = (hash) => {
    const opening = !open.has(hash);
    setOpen((prev) => {
      const next = new Set(prev);
      if (opening) next.add(hash);
      else next.delete(hash);
      return next;
    });
    if (opening && !(hash in files)) loadFiles(hash);
  };

  const groups = useMemo(() => groupByDay(history.commits), [history.commits]);
  const empty = !history.loading && !history.error && history.commits.length === 0;
  const scopeName = (scope || path).split('/').pop();

  return (
    <div className="commit-history">
      {(scope || path) && (
        <div className="commit-history__scope">
          {scope ? (
            <span className="commit-history__chip" title={scope}>
              <span className="commit-history__chip-label">{t('history.scopeOnly', { name: scopeName })}</span>
              <button
                type="button"
                className="icon-btn icon-btn--xs icon-btn--quiet"
                title={t('history.scopeClear')}
                aria-label={t('history.scopeClear')}
                onClick={() => setScope('')}
              >
                <IconX size={10} />
              </button>
            </span>
          ) : (
            <button type="button" className="btn btn--ghost btn--xs" title={path} onClick={() => setScope(path)}>
              {t('history.scopeOnly', { name: scopeName })}
            </button>
          )}
        </div>
      )}
      <div
        className="commit-history__list ws-list"
        role="tree"
        aria-label={t('panel.history')}
        tabIndex={0}
        onKeyDown={handleKeyDown}
      >
        {history.loading && (
          <div className="ws-hint" role="none">
            {t('tree.loading')}
          </div>
        )}
        {history.error && (
          <div className="ws-hint" role="none">
            {t('history.loadError')}
          </div>
        )}
        {empty && (
          <div className="ws-hint" role="none">
            {t('history.empty')}
          </div>
        )}
        {groups.map((group) => {
          const label = dayLabel(group.date, i18n.language);
          const title = label.key ? t(label.key) : label.text;
          return (
            <div
              key={`${group.key} ${group.commits[0].hash}`}
              className="commit-history__day"
              role="group"
              aria-label={title}
            >
              <div className="commit-history__day-title">
                {title}
                <span className="commit-history__count">{group.commits.length}</span>
              </div>
              {group.commits.map((c) => (
                <CommitPlate
                  key={c.hash}
                  commit={c}
                  open={open.has(c.hash)}
                  outgoing={!!history.outgoing.has(c.hash)}
                  files={files[c.hash]}
                  selectedPath={c.hash === commit ? path : null}
                  onToggle={toggle}
                  onRetry={loadFiles}
                  onOpenFile={onOpenFile}
                  onOpenDetail={setDetail}
                />
              ))}
            </div>
          );
        })}
      </div>
      {history.commits.length > 0 && (
        <div className="commit-history__footer">
          {history.truncated && (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              disabled={history.moreLoading}
              onClick={history.loadMore}
            >
              {history.moreLoading ? t('tree.loading') : t('history.more')}
            </button>
          )}
          {history.moreError && <span className="commit-history__error">{t('history.loadError')}</span>}
          <span className="commit-history__shown">
            {t(history.truncated ? 'history.shown' : 'history.shownAll', {
              count: history.commits.length,
            })}
          </span>
        </div>
      )}
      {detail && (
        <CommitDetailModal
          hash={detail}
          project={project}
          onClose={() => setDetail(null)}
          onOpenSnapshot={(hash) => {
            setDetail(null);
            onOpenSnapshot(hash);
          }}
        />
      )}
    </div>
  );
};

export default CommitHistory;
