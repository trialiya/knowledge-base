import React, { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import OperationRow from '../common/OperationRow';
import useJobStream from './useJobStream';
import { IconDownload } from '../../icons';
import api from '../../api/documentsApi';

// ─── Операция: выгрузка дерева в серверную папку ─────────────────────────────
// Идёт через потоковый эндпоинт: на большом дереве обычный POST молчит до
// последнего записанного файла, и «работает» неотличимо от «повисло». Здесь
// видно, какой узел пишется прямо сейчас.

const ExportOperation = () => {
  const { t } = useTranslation('settings');
  const [meta, setMeta] = useState(true);

  const [status, run] = useJobStream(useCallback((signal) => api.exportStream(meta, signal), [meta]));

  return (
    <OperationRow
      icon={<IconDownload size={18} />}
      title={t('admin.bulk.export.title')}
      desc={t('admin.bulk.export.desc', { meta: meta ? t('admin.bulk.export.metaSuffix') : '' })}
      labels={{
        run: t('admin.bulk.export.run'),
        running: t('admin.bulk.export.running'),
        doneBadge: t('admin.bulk.export.doneBadge'),
        done: t('admin.bulk.export.done'),
        errorBadge: t('admin.bulk.export.errorBadge'),
        error: t('admin.bulk.export.error'),
      }}
      state={status.state}
      onRun={run}
      runVariant="ghost"
      progress={
        <>
          <span>{t('admin.bulk.progress.nodes', { count: status.processed })}</span>
          <span className="set-op__progress-path">{status.path}</span>
        </>
      }
      done={status.summary ? t('admin.bulk.export.doneFiles', { count: status.summary.files }) : undefined}
    >
      <label className="admin-check">
        <input
          type="checkbox"
          checked={meta}
          onChange={(e) => setMeta(e.target.checked)}
          disabled={status.state === 'running'}
        />
        {t('admin.bulk.export.metaCheckbox')}
      </label>
    </OperationRow>
  );
};

export default ExportOperation;
