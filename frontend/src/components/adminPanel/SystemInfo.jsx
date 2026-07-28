import React from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '../common/SettingsShell';
import ConfigGroup, { ConfigRow, ConfigBoolRow, useDurationFormat } from '../common/ConfigGroup';
import useConfigSnapshot from '../common/useConfigSnapshot';
import settingsApi from '../../api/settingsApi';
import { formatDateTime } from '../../utils/formatting';

/**
 * Как устроен сам сервер: профиль, база, индексируемый репозиторий, папка
 * экспорта, учётная запись. Всё read-only и без секретов — бэкенд собирает
 * ответ по полям и не отдаёт ни ключей, ни паролей (см. SystemInfoController).
 */
const SystemInfo = () => {
  const { t } = useTranslation('settings');
  const { data: info, error } = useConfigSnapshot(settingsApi.getSystemInfo);

  return (
    <ConfigGroup title={t('admin.system.title')} subtitle={t('admin.system.subtitle')} data={info} error={error}>
      <SystemSections info={info} />
    </ConfigGroup>
  );
};

const SystemSections = ({ info }) => {
  const { t, i18n } = useTranslation('settings');
  const duration = useDurationFormat();
  const { application, database, git, documents, security } = info;

  return (
    <>
      {/* ── Приложение ── */}
      <SettingsSection label={t('admin.system.app.label')}>
        <ConfigRow label={t('admin.system.app.name')} value={application.name} />
        <ConfigRow label={t('admin.system.app.profiles')} value={application.profiles.join(', ')} />
        <ConfigRow label={t('admin.system.app.port')} value={application.port} />
        <ConfigRow label={t('admin.system.app.java')} value={application.javaVersion} />
        <ConfigRow
          label={t('admin.system.app.startedAt')}
          value={formatDateTime(application.startedAt, i18n.language)}
        />
        <ConfigRow label={t('admin.system.app.uptime')} value={duration(application.uptimeSeconds)} />
      </SettingsSection>

      {/* ── База данных ── */}
      <SettingsSection label={t('admin.system.database.label')}>
        <ConfigRow label={t('admin.system.database.url')} value={database.url} empty={t('config.notSet')} />
        <ConfigRow label={t('admin.system.database.driver')} value={database.driver} />
        <ConfigRow label={t('admin.system.database.username')} value={database.username} />
        <ConfigRow
          label={t('admin.system.database.flywayLocations')}
          value={database.flywayLocations}
          empty={t('config.notSet')}
        />
        <ConfigRow
          label={t('admin.system.database.schemaVersion')}
          value={database.schemaVersion || t('config.unknown')}
        />
      </SettingsSection>

      {/* ── Репозиторий ── */}
      <SettingsSection label={t('admin.system.git.label')}>
        <ConfigRow label={t('admin.system.git.projectPath')} value={git.projectPath} empty={t('config.notSet')} />
        <ConfigBoolRow label={t('admin.system.git.editEnabled')} value={git.editEnabled} />
        <ConfigBoolRow label={t('admin.system.git.writable')} value={git.writable} />
      </SettingsSection>

      {/* ── Экспорт документов ── */}
      <SettingsSection label={t('admin.system.documents.label')}>
        <ConfigRow
          label={t('admin.system.documents.exportPath')}
          value={documents.exportPath}
          empty={t('admin.system.documents.exportPathEmpty')}
        />
        <ConfigBoolRow label={t('admin.system.documents.replace')} value={documents.replace} />
      </SettingsSection>

      {/* ── Доступ ── */}
      <SettingsSection label={t('admin.system.security.label')}>
        <ConfigRow label={t('admin.system.security.username')} value={security.username} />
        <p className="config-note">{t('admin.system.security.note')}</p>
      </SettingsSection>
    </>
  );
};

export default SystemInfo;
