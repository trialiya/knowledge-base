import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import { IconFolder, IconList, IconX } from '@/icons/index';
import CommitDetailSummary from './CommitDetailSummary';
import useSnapshotCommit from '../commit/useSnapshotCommit';
import ChangesList from '../changes/ChangesList';
import ChangeDiffView from '../changes/ChangeDiffView';
import useChangeDiff from '../changes/useChangeDiff';
import '../filesPanel.css';

/**
 * Детали коммита из ленты — как детали вызова инструмента в чате: открываются
 * поверх, ничего не меняя в адресе, и закрываются туда же, откуда открыли.
 *
 * Две колонки на всю высоту: слева сводка и все изменённые файлы (плашка в
 * ленте показывает лишь первые), справа патч выбранного — по одному запросу на
 * файл, как в режиме «Изменения». Колонки прокручиваются каждая сама по себе:
 * длинный список не уводит патч из виду, и наоборот.
 *
 * Файлы по умолчанию деревом: в коммите их бывает десятки, и каталог —
 * единственное, что связывает удалённый `csv/X` с появившимся `excel/X`.
 * Выбор раскладки живёт, пока открыто окно: память панели (changesLayout)
 * — о незакоммиченном, где дефолт обратный.
 */
const CommitDetailModal = ({ hash, project, onClose, onOpenSnapshot }) => {
  const { t } = useTranslation('files');
  const snapshot = useSnapshotCommit({ project, rev: hash });
  const [selected, setSelected] = useState(null);
  const [flat, setFlat] = useState(false);
  const files = snapshot.entries;
  const current = files.find((entry) => entry.path === selected) ?? files[0] ?? null;
  const diff = useChangeDiff({
    project,
    path: current?.path ?? null,
    rev: hash,
    enabled: !!current,
  });
  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);

  return (
    <ModalShell onClose={onClose} className="commit-detail">
      <div className="commit-detail__body">
        <aside className="commit-detail__side">
          <CommitDetailSummary
            hash={hash}
            project={project}
            commit={snapshot.commit}
            loading={snapshot.loading}
            error={snapshot.error}
            onOpenSnapshot={onOpenSnapshot}
          />
          {snapshot.commit && (
            <>
              <div className="commit-detail__files-head">
                <span className="commit-detail__files-label">
                  {t('commit.filesSummary', { count: files.length, additions, deletions })}
                </span>
                <button
                  type="button"
                  className="icon-btn icon-btn--sm commit-detail__layout"
                  aria-pressed={!flat}
                  title={flat ? t('changes.layoutTree') : t('changes.layoutFlat')}
                  onClick={() => setFlat(!flat)}
                >
                  {flat ? <IconFolder size={16} /> : <IconList size={15} />}
                </button>
              </div>
              <ChangesList
                tracked={files}
                untracked={[]}
                flat={flat}
                selectedPath={current?.path}
                onSelect={(entry) => setSelected(entry.path)}
                snapshot
              />
            </>
          )}
        </aside>
        <section className="commit-detail__main">
          <div className="commit-detail__file-head">
            {current && <FileTitle entry={current} />}
            <button type="button" className="icon-btn commit-detail__close" onClick={onClose} title={t('common:close')}>
              <IconX size={14} />
            </button>
          </div>
          {current && (
            <div className="commit-detail__diff">
              <ChangeDiffView diff={diff} />
            </div>
          )}
        </section>
      </div>
    </ModalShell>
  );
};

/** Шапка патча: статус, имя и приглушённый каталог — как строка списка слева. */
const FileTitle = ({ entry }) => {
  const { t } = useTranslation('files');
  const slash = entry.path.lastIndexOf('/');
  const name = entry.path.slice(slash + 1);
  const dir = slash > 0 ? entry.path.slice(0, slash) : '';
  const status = t(`changes.status.${entry.status}`, { defaultValue: entry.status });

  return (
    <span className="commit-detail__file" title={entry.oldPath ? `${entry.path} ← ${entry.oldPath}` : entry.path}>
      <span className={`file-changes__status file-changes__status--${entry.status}`} title={status}>
        {entry.status}
      </span>
      <span className="commit-detail__file-name">{name}</span>
      {dir && <span className="commit-detail__file-dir">{dir}</span>}
    </span>
  );
};

export default CommitDetailModal;
