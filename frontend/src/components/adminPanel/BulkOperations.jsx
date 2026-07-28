import React, { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { ConfigRow } from '../common/ConfigGroup';
import OperationRow, { useOperation } from '../common/OperationRow';
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

  const [exportMeta, setExportMeta] = useState(true);
  // exportToFolder отдаёт сырой Response: неуспех здесь — это res.ok === false,
  // а не исключение (см. шапку documentsApi).
  const [exportState, runExport] = useOperation(
    useCallback(async () => (await api.exportToFolder(exportMeta)).ok, [exportMeta]),
  );

  return (
    <>
      <SettingsContentHead title={t('admin.bulk.title')} subtitle={t('admin.bulk.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.bulk.sectionLabel')} rows>
          <OperationRow
            icon={<IconDownload size={18} />}
            title={t('admin.bulk.export.title')}
            desc={t('admin.bulk.export.desc', {
              meta: exportMeta ? t('admin.bulk.export.metaSuffix') : '',
            })}
            labels={{
              run: t('admin.bulk.export.run'),
              running: t('admin.bulk.export.running'),
              doneBadge: t('admin.bulk.export.doneBadge'),
              done: t('admin.bulk.export.done'),
              errorBadge: t('admin.bulk.export.errorBadge'),
              error: t('admin.bulk.export.error'),
            }}
            state={exportState}
            onRun={runExport}
            runVariant="ghost"
          >
            <label className="admin-check">
              <input
                type="checkbox"
                checked={exportMeta}
                onChange={(e) => setExportMeta(e.target.checked)}
                disabled={exportState === 'running'}
              />
              {t('admin.bulk.export.metaCheckbox')}
            </label>
          </OperationRow>
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
