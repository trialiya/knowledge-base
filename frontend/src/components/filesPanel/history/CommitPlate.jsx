import { useTranslation } from 'react-i18next';
import { IconChevron, IconExpand } from '@/icons/index';
import CopyButton from '@/components/common/ui/CopyButton';
import ChangeRow from '../changes/ChangeRow';
import { timeOf } from './historyDays';

/** Сколько файлов раскрытая плашка показывает сама — остальные в деталях коммита. */
export const INLINE_FILES = 8;

/**
 * Плашка коммита — та же, что плашка вызова инструмента в чате: заголовок,
 * строка подробностей и раскрытие на месте. Под раскрытой — изменённые файлы,
 * которые приходят только по раскрытию (`files` — ответ /commit без патчей,
 * `undefined` — ещё не спрашивали или ещё в пути).
 *
 * Цвет полосы слева — ушёл коммит на remote или нет, как у вызова — его исход:
 * неотправленный ещё можно переписать, и это первое, что про него хочется знать.
 *
 * Строка — `treeitem` ленты: стрелки ходят по ней и по файлам под ней, ←/→
 * сворачивают и раскрывают её через шеврон (см. useListNavigation).
 */
const CommitPlate = ({ commit, open, outgoing, files, selectedPath, onToggle, onRetry, onOpenFile, onOpenDetail }) => {
  const { t, i18n } = useTranslation('files');
  const loading = open && files === undefined;
  const failed = open && files === null;
  const shown = files ? files.slice(0, INLINE_FILES) : [];
  const additions = files ? files.reduce((sum, f) => sum + f.additions, 0) : 0;
  const deletions = files ? files.reduce((sum, f) => sum + f.deletions, 0) : 0;

  return (
    <div className="commit-plate-wrap" role="none">
      <div
        data-ws-item
        role="treeitem"
        aria-level={1}
        aria-expanded={open}
        tabIndex={-1}
        className={`commit-plate${outgoing ? ' commit-plate--outgoing' : ''}${open ? ' commit-plate--open' : ''}`}
        title={commit.message}
        onClick={() => onToggle(commit.hash)}
      >
        <span className="commit-plate__chevron" data-ws-chevron>
          <IconChevron open={open} />
        </span>
        <span className="commit-plate__body">
          <span className="commit-plate__subject">{commit.message}</span>
          <span className="commit-plate__meta">
            <span className="commit-plate__hash">{commit.shortHash}</span>
            <span>{commit.author}</span>
            <span>{timeOf(commit.date, i18n.language)}</span>
            {outgoing && (
              <span className="commit-plate__badge" title={t('history.outgoingHint')}>
                {t('history.outgoing')}
              </span>
            )}
          </span>
        </span>
        {/* Действия не должны раскрывать плашку заодно. */}
        <span className="commit-plate__actions" role="none" onClick={(e) => e.stopPropagation()}>
          <CopyButton value={commit.hash} title={t('history.copyHash')} className="icon-btn--xs icon-btn--quiet" />
          <button
            type="button"
            className="icon-btn icon-btn--xs icon-btn--quiet"
            title={t('history.openDetail')}
            aria-label={t('history.openDetail')}
            onClick={() => onOpenDetail(commit.hash)}
          >
            <IconExpand size={12} />
          </button>
        </span>
      </div>

      {open && (
        <div className="commit-plate__children" role="group">
          {loading && <div className="commit-plate__note">{t('history.filesLoading')}</div>}
          {failed && (
            <div className="commit-plate__note commit-plate__note--error">
              {t('history.filesError')}
              <button type="button" className="btn btn--ghost btn--xs" onClick={() => onRetry(commit.hash)}>
                {t('history.retry')}
              </button>
            </div>
          )}
          {files && (
            <>
              <div className="commit-plate__note">
                {t('commit.filesSummary', {
                  count: files.length,
                  additions,
                  deletions,
                })}
              </div>
              {shown.map((entry) => (
                <ChangeRow
                  key={entry.path}
                  entry={entry}
                  showDir
                  role="treeitem"
                  level={2}
                  selected={entry.path === selectedPath}
                  onSelect={(e) => onOpenFile(e.path, commit.hash)}
                />
              ))}
              {files.length > shown.length && (
                <button
                  type="button"
                  className="btn btn--ghost btn--xs commit-plate__more"
                  onClick={() => onOpenDetail(commit.hash)}
                >
                  {t('history.allFiles', { count: files.length })}
                </button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default CommitPlate;
