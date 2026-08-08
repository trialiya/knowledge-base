import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { ConfigRow } from '../common/ConfigGroup';
import useConfigSnapshot from '../common/useConfigSnapshot';
import ExportOperation from './ExportOperation';
import ArchiveDownload from './ArchiveDownload';
import ImportOperation from './ImportOperation';
import settingsApi from '../../api/settingsApi';

// ─── Группа: массовые операции ────────────────────────────────────────────────
// Обе стороны обмена с файловой системой: выгрузка дерева в серверную папку и
// обратная загрузка из неё через сравнение. Сами операции живут в отдельных
// компонентах — у импорта есть свой список различий с выбором, и держать его
// здесь значило бы смешать две независимые части.
//
// Путь показан рядом с обеими: без него «не удалось выгрузить» неотличимо от
// незаданного DOCUMENTS_EXPORT_PATH, а это самая частая причина отказа.

const BulkOperations = () => {
  const { t } = useTranslation('settings');
  const { data: info } = useConfigSnapshot(settingsApi.getSystemInfo);

  return (
    <>
      <SettingsContentHead title={t('admin.bulk.title')} subtitle={t('admin.bulk.subtitle')} />
      <div className="settings-content__body">
        <SettingsSection label={t('admin.bulk.sectionLabel')} rows>
          <ExportOperation />
          <ArchiveDownload />
        </SettingsSection>

        <SettingsSection label={t('admin.bulk.importSectionLabel')} rows>
          <ImportOperation />
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
