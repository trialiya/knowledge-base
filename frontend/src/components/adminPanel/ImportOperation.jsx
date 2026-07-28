import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import OperationRow from '../common/OperationRow';
import useJobStream from './useJobStream';
import SyncDiffList from './SyncDiffList';
import { selectAllActionable, summarizeSelection, toggleEntry } from './syncSelection';
import { IconRefreshCw, IconUpload } from '../../icons';
import api from '../../api/documentsApi';

// ─── Операции: сравнение и импорт из серверной папки ─────────────────────────
// Сначала «Сравнить» — читающая операция, которая ничего не пишет и отвечает на
// вопрос «что изменится, если импортировать». Потом «Импортировать» — по
// отмеченным записям. Разделение и делает повторный импорт безопасным: без него
// каждый прогон просто создавал бы дерево заново.

const ImportOperation = () => {
  const { t } = useTranslation('settings');

  const [entries, setEntries] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [showUnchanged, setShowUnchanged] = useState(false);
  const [deleteMissing, setDeleteMissing] = useState(false);

  const collectEntry = useCallback((entry) => {
    setEntries((prev) => [...prev, entry]);
  }, []);

  const resetDiff = useCallback(() => {
    setEntries([]);
    setSelected(new Set());
  }, []);

  const [diffStatus, runDiff] = useJobStream(
    useCallback((signal) => api.importDiff(null, signal), []),
    { onEntry: collectEntry, onStart: resetDiff },
  );

  const [importStatus, runImport] = useJobStream(
    useCallback(
      (signal) => api.importApply({ paths: [...selected], deleteMissing }, signal),
      [selected, deleteMissing],
    ),
    { onStart: () => setSelected(new Set()) },
  );

  // Запись в базу делает список различий неправдой: строки остались бы со
  // старыми статусами, и «новый» висел бы на уже созданном узле. Сравнение
  // читающее и дешёвое, поэтому после импорта оно просто повторяется — вместо
  // того чтобы оставить человека с устаревшим списком и надеждой, что он
  // догадается нажать «Сравнить».
  useEffect(() => {
    if (importStatus.state === 'done') runDiff();
  }, [importStatus.state, runDiff]);

  const chosen = useMemo(() => summarizeSelection(entries, selected), [entries, selected]);
  const total = chosen.added + chosen.modified + chosen.missing;
  const busy = diffStatus.state === 'running' || importStatus.state === 'running';

  return (
    <>
      <OperationRow
        icon={<IconRefreshCw size={18} />}
        title={t('admin.bulk.diff.title')}
        desc={t('admin.bulk.diff.desc')}
        labels={{
          run: t('admin.bulk.diff.run'),
          running: t('admin.bulk.diff.running'),
          doneBadge: t('admin.bulk.diff.doneBadge'),
          done: t('admin.bulk.diff.done'),
          errorBadge: t('admin.bulk.diff.errorBadge'),
          error: t('admin.bulk.diff.error'),
        }}
        state={diffStatus.state}
        onRun={runDiff}
        runVariant="ghost"
        runDisabled={busy}
        progress={
          <>
            <span>{t('admin.bulk.progress.nodes', { count: diffStatus.processed })}</span>
            <span className="set-op__progress-path">{diffStatus.path}</span>
          </>
        }
        done={
          diffStatus.summary
            ? t('admin.bulk.diff.doneCounts', {
                added: diffStatus.summary.added,
                modified: diffStatus.summary.modified,
                missing: diffStatus.summary.missing,
                unchanged: diffStatus.summary.unchanged,
              })
            : undefined
        }
        error={diffStatus.error || undefined}
      />

      {(entries.length > 0 || diffStatus.state === 'running') && (
        <>
          <div className="sync-diff__actions">
            <button
              className="btn btn--ghost btn--sm"
              onClick={() => setSelected(selectAllActionable(entries))}
              disabled={busy}
            >
              {t('admin.bulk.diff.selectAll')}
            </button>
            <button
              className="btn btn--ghost btn--sm"
              onClick={() => setSelected(new Set())}
              disabled={busy || selected.size === 0}
            >
              {t('admin.bulk.diff.selectNone')}
            </button>
          </div>

          <SyncDiffList
            entries={entries}
            selected={selected}
            onToggle={(path) => setSelected((prev) => toggleEntry(entries, prev, path))}
            showUnchanged={showUnchanged}
            onShowUnchanged={setShowUnchanged}
            disabled={busy}
          />
        </>
      )}

      <OperationRow
        icon={<IconUpload size={18} />}
        title={t('admin.bulk.import.title')}
        desc={t('admin.bulk.import.desc', { count: total })}
        labels={{
          run: t('admin.bulk.import.run'),
          running: t('admin.bulk.import.running'),
          doneBadge: t('admin.bulk.import.doneBadge'),
          done: t('admin.bulk.import.done'),
          errorBadge: t('admin.bulk.import.errorBadge'),
          error: t('admin.bulk.import.error'),
        }}
        state={importStatus.state}
        onRun={runImport}
        runDisabled={busy || total === 0}
        progress={
          <>
            <span>{t('admin.bulk.progress.nodes', { count: importStatus.processed })}</span>
            <span className="set-op__progress-path">{importStatus.path}</span>
          </>
        }
        done={
          importStatus.summary
            ? t('admin.bulk.import.doneCounts', {
                created: importStatus.summary.created,
                updated: importStatus.summary.updated,
                deleted: importStatus.summary.deleted,
              })
            : undefined
        }
        error={importStatus.error || undefined}
      >
        <label className="admin-check">
          <input
            type="checkbox"
            checked={deleteMissing}
            onChange={(e) => setDeleteMissing(e.target.checked)}
            disabled={busy}
          />
          {t('admin.bulk.import.deleteMissing', { count: chosen.missing })}
        </label>
      </OperationRow>
    </>
  );
};

export default ImportOperation;
