import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { ConfigRow } from '../common/ConfigGroup';
import useConfigSnapshot from '../common/useConfigSnapshot';
import { IconDownload } from '../../icons';
import api from '../../api/documentsApi';
import settingsApi from '../../api/settingsApi';

// ─── Группа: массовые операции ────────────────────────────────────────────────
// Экспорт/импорт и пакетная обработка документов. Экспорт вызывает текущий
// серверный метод — выгрузку дерева в папку kb.documents.export-path. Сам путь
// показан рядом: без него «не удалось выгрузить» неотличимо от незаданного
// DOCUMENTS_EXPORT_PATH, а это самая частая причина отказа.

const BulkOperations = () => {
  const { t } = useTranslation('settings');
  const { data: info } = useConfigSnapshot(settingsApi.getSystemInfo);

  // idle | running | done | error
  const [exportState, setExportState] = useState('idle');
  const [exportMeta, setExportMeta] = useState(true);

  const runExport = async () => {
    if (exportState === 'running') return;
    setExportState('running');
    try {
      const res = await api.exportToFolder(exportMeta);
      setExportState(res.ok ? 'done' : 'error');
    } catch {
      setExportState('error');
    }
  };

  return (
    <>
      <SettingsContentHead title={t('admin.bulk.title')} subtitle={t('admin.bulk.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.bulk.sectionLabel')} rows>
          <div className="set-op">
            <span className="set-op__icon">
              <IconDownload size={18} />
            </span>
            <div className="set-op__text">
              <div className="set-op__title">{t('admin.bulk.export.title')}</div>
              <div className="set-op__desc">
                {t('admin.bulk.export.desc', {
                  meta: exportMeta ? t('admin.bulk.export.metaSuffix') : '',
                })}
              </div>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={exportMeta}
                  onChange={(e) => setExportMeta(e.target.checked)}
                  disabled={exportState === 'running'}
                />
                {t('admin.bulk.export.metaCheckbox')}
              </label>
              {exportState === 'done' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--ok">{t('admin.bulk.export.doneBadge')}</span>
                  <span>{t('admin.bulk.export.done')}</span>
                </div>
              )}
              {exportState === 'error' && (
                <div className="admin-status admin-status--inline">
                  <span className="admin-badge admin-badge--error">{t('admin.bulk.export.errorBadge')}</span>
                  <span>{t('admin.bulk.export.error')}</span>
                </div>
              )}
            </div>
            <button className="btn btn--ghost" onClick={runExport} disabled={exportState === 'running'}>
              {exportState === 'running' ? t('admin.bulk.export.running') : t('admin.bulk.export.run')}
            </button>
          </div>
        </SettingsSection>

        {info && (
          <SettingsSection label={t('admin.bulk.targetLabel')}>
            <ConfigRow
              label={t('admin.bulk.exportPath')}
              value={info.documents.exportPath}
              empty={t('admin.system.documents.exportPathEmpty')}
            />
          </SettingsSection>
        )}
      </div>
    </>
  );
};

export default BulkOperations;
