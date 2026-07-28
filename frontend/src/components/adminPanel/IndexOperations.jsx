import React from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { ConfigRow, ConfigStatusRow, useDurationFormat } from '../common/ConfigGroup';
import OperationRow, { useOperation } from '../common/OperationRow';
import useConfigSnapshot from '../common/useConfigSnapshot';
import { IconRefresh } from '../../icons';
import documentsApi from '../../api/documentsApi';
import settingsApi from '../../api/settingsApi';

/**
 * Семантический индекс: единственная реально работающая операция (полная
 * переиндексация, POST /api/documents/admin/reindex) и настройки фоновой
 * очереди эмбеддингов рядом с ней — когда переиндексация «висит», объяснение
 * обычно в них (сколько воркеров, как часто опрос, сколько попыток).
 *
 * Группа не завёрнута в ConfigGroup: кнопка должна работать даже если снимок
 * настроек не загрузился — это разные источники данных и разные отказы.
 */
const IndexOperations = () => {
  const { t } = useTranslation('settings');
  const { data: info } = useConfigSnapshot(settingsApi.getSystemInfo);
  const [state, runReindex] = useOperation(documentsApi.reindex);

  return (
    <>
      <SettingsContentHead title={t('admin.index.title')} subtitle={t('admin.index.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.index.sectionLabel')} rows>
          <OperationRow
            icon={<IconRefresh size={18} />}
            title={t('admin.index.reindex.title')}
            desc={t('admin.index.reindex.desc')}
            labels={{
              run: t('admin.index.reindex.run'),
              running: t('admin.index.reindex.running'),
              doneBadge: t('admin.index.reindex.doneBadge'),
              done: t('admin.index.reindex.done'),
              errorBadge: t('admin.index.reindex.errorBadge'),
              error: t('admin.index.reindex.error'),
            }}
            state={state}
            onRun={runReindex}
          />
        </SettingsSection>

        {info && <QueueSection indexing={info.indexing} />}
      </div>
    </>
  );
};

const QueueSection = ({ indexing }) => {
  const { t } = useTranslation('settings');
  const duration = useDurationFormat();

  return (
    <SettingsSection label={t('admin.index.queue.label')}>
      <ConfigRow label={t('admin.index.queue.workers')} value={indexing.workers} />
      <ConfigRow
        label={t('admin.index.queue.pollBatchSize')}
        value={t('admin.index.queue.tasksValue', { count: indexing.pollBatchSize })}
      />
      <ConfigRow label={t('admin.index.queue.pollInterval')} value={duration(indexing.pollIntervalMs / 1000)} />
      <ConfigRow label={t('admin.index.queue.maxAttempts')} value={indexing.maxAttempts} />
      <ConfigRow label={t('admin.index.queue.retryBackoff')} value={duration(indexing.retryBackoffSeconds)} />
      <ConfigRow label={t('admin.index.queue.stuckTimeout')} value={duration(indexing.stuckTimeoutMinutes * 60)} />
      <ConfigRow label={t('admin.index.queue.stuckCheck')} value={duration(indexing.stuckCheckMs / 1000)} />
      <ConfigRow
        label={t('admin.index.queue.cleanupRetention')}
        value={t('config.daysValue', { count: indexing.cleanupRetentionDays })}
      />
      <ConfigStatusRow label={t('admin.index.queue.cache')} on={indexing.cacheEnabled} />
      <ConfigRow
        label={t('admin.index.queue.cacheTtl')}
        value={t('config.daysValue', { count: indexing.cacheTtlDays })}
      />
      <ConfigRow
        label={t('admin.index.queue.cacheCleanupCron')}
        value={indexing.cacheCleanupCron}
        empty={t('config.notSet')}
      />
    </SettingsSection>
  );
};

export default IndexOperations;
