import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import useListNavigation from '@/components/common/search/useListNavigation';
import shortRev from '@/components/common/git/shortRev';
import { IconCommit, IconX } from '@/icons/index';
import CommitInfo from '../commit/CommitInfo';
import useSnapshotCommit from '../commit/useSnapshotCommit';
import ChangeRow from '../changes/ChangeRow';
import ChangeDiffView from '../changes/ChangeDiffView';
import useChangeDiff from '../changes/useChangeDiff';

/**
 * Детали коммита из ленты — как детали вызова инструмента в чате: открываются
 * поверх, ничего не меняя в адресе, и закрываются туда же, откуда открыли.
 *
 * Сводка — та же, что на вкладке «Коммит» снимка (CommitInfo): один и тот же
 * коммит не должен описываться в двух местах по-разному. Ниже — все изменённые
 * файлы (плашка в ленте показывает лишь первые) и патч выбранного, по одному
 * запросу на файл, как в режиме «Изменения».
 */
const CommitDetailModal = ({ hash, project, onClose, onOpenSnapshot }) => {
  const { t } = useTranslation('files');
  const handleKeyDown = useListNavigation();
  const snapshot = useSnapshotCommit({ project, rev: hash });
  const [selected, setSelected] = useState(null);
  const files = snapshot.entries;
  const current = selected ?? files[0]?.path ?? null;
  const diff = useChangeDiff({
    project,
    path: current,
    rev: hash,
    enabled: !!current,
  });

  return (
    <ModalShell onClose={onClose} className="commit-detail">
      <div className="commit-detail__header">
        <span className="commit-detail__title">
          <IconCommit size={16} />
          {t('history.detailTitle', { hash: shortRev(hash) })}
        </span>
        <button type="button" className="btn btn--ghost btn--sm" onClick={() => onOpenSnapshot(hash)}>
          {t('history.openSnapshot')}
        </button>
        <button type="button" className="icon-btn" onClick={onClose} title={t('common:close')}>
          <IconX size={14} />
        </button>
      </div>
      <div className="commit-detail__body">
        <CommitInfo
          rev={hash}
          project={project}
          commit={snapshot.commit}
          loading={snapshot.loading}
          error={snapshot.error}
          changesShown
        />
        {files.length > 0 && (
          <section className="commit-detail__files">
            <div
              className="commit-detail__list ws-list"
              role="listbox"
              aria-label={t('panel.commitChanges')}
              tabIndex={0}
              onKeyDown={handleKeyDown}
            >
              {files.map((entry) => (
                <ChangeRow
                  key={entry.path}
                  entry={entry}
                  showDir
                  selected={entry.path === current}
                  onSelect={(e) => setSelected(e.path)}
                />
              ))}
            </div>
            <div className="commit-detail__diff">
              <ChangeDiffView diff={diff} />
            </div>
          </section>
        )}
      </div>
    </ModalShell>
  );
};

export default CommitDetailModal;
