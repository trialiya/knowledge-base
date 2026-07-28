import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { ConfigRow, ConfigStatusRow, useDurationFormat } from '../common/ConfigGroup';
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

  // idle | running | done | error
  const [state, setState] = useState('idle');

  const runReindex = async () => {
    if (state === 'running') return;
    setState('running');
    try {
      await documentsApi.reindex();
      setState('done');
    } catch {
      setState('error');
    }
  };

  return (
    <>
      <SettingsContentHead title={t('admin.index.title')} subtitle={t('admin.index.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.index.sectionLabel')} rows>
          <div className="set-op">
            <span className="set-op__icon">
              <IconRefresh size={18} />
            </span>
            <div className="set-op__text">
              <div className="set-op__title">{t('admin.index.reindex.title')}</div>
              <div className="set-op__desc">{t('admin.index.reindex.desc')}</div>
              {state === 'done' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--ok">{t('admin.index.reindex.doneBadge')}</span>
                  <span>{t('admin.index.reindex.done')}</span>
                </div>
              )}
              {state === 'error' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--error">{t('admin.index.reindex.errorBadge')}</span>
                  <span>{t('admin.index.reindex.error')}</span>
                </div>
              )}
            </div>
            <button className="btn btn--primary" onClick={runReindex} disabled={state === 'running'}>
              {state === 'running' ? t('admin.index.reindex.running') : t('admin.index.reindex.run')}
            </button>
          </div>
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
