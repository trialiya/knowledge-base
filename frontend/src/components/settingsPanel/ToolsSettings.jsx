import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import ConfigGroup, {
  ConfigRow,
  ConfigStatusRow,
  ConfigBoolRow,
  ConfigTags,
  ConfigBlock,
} from '@/components/common/config/ConfigGroup';
import useConfigSnapshot from '@/components/common/config/useConfigSnapshot';
import ToolCatalog from './ToolCatalog';
import settingsApi from '@/api/settingsApi';
import { formatFileSize } from '@/utils/formatting';

/**
 * Что ассистент умеет помимо встроенных read-only инструментов: готовые режимы,
 * правка файлов в рабочем дереве, внешние MCP-серверы и лимиты вложений.
 */
const ToolsSettings = () => {
  const { t } = useTranslation('settings');
  const { data: config, error } = useConfigSnapshot(settingsApi.getAiConfig);

  return (
    <ConfigGroup title={t('tools.title')} subtitle={t('tools.subtitle')} data={config} error={error}>
      <ToolsSections config={config} />
    </ConfigGroup>
  );
};

const ToolsSections = ({ config }) => {
  const { t } = useTranslation('settings');
  const { modes, git, mcp, uploads } = config.tools;

  return (
    <>
      {/* ── Каталог инструментов (свой запрос, см. ToolCatalog) ── */}
      <ToolCatalog />

      {/* ── Режимы ассистента ── */}
      <SettingsSection label={t('tools.modes.label')} rows>
        {modes.length === 0 ? (
          <div className="set-op">
            <div className="set-op__text">
              <div className="set-op__desc">{t('tools.modes.empty')}</div>
            </div>
          </div>
        ) : (
          modes.map((mode) => (
            <div key={mode.id} className="model-row">
              <span className="model-row__name">{mode.id}</span>
              <span className="model-row__label">{mode.label}</span>
            </div>
          ))
        )}
      </SettingsSection>

      {/* ── Правка файлов ── */}
      <SettingsSection label={t('tools.git.label')}>
        <ConfigBoolRow label={t('tools.git.editEnabled')} value={git.editEnabled} />
        <ConfigBoolRow label={t('tools.git.editActive')} value={git.editActive} />
        {git.editEnabled && !git.editActive && <p className="config-note">{t('tools.git.readOnlyNote')}</p>}
      </SettingsSection>

      {/* ── Внешние MCP-серверы ── */}
      <SettingsSection label={t('tools.mcp.label')}>
        <ConfigStatusRow label={t('tools.mcp.status')} on={mcp.enabled} />
        <ConfigBlock label={t('tools.mcp.connections')}>
          <ConfigTags
            items={mcp.connections.map((c) => `${c.name} · ${c.transport}`)}
            empty={t('tools.mcp.connectionsEmpty')}
          />
        </ConfigBlock>
      </SettingsSection>

      {/* ── Лимиты вложений ── */}
      <SettingsSection label={t('tools.uploads.label')}>
        <ConfigRow label={t('tools.uploads.maxFileSize')} value={formatFileSize(uploads.maxFileSizeBytes)} />
        <ConfigRow label={t('tools.uploads.maxRequestSize')} value={formatFileSize(uploads.maxRequestSizeBytes)} />
      </SettingsSection>
    </>
  );
};

export default ToolsSettings;
