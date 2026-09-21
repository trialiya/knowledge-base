import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import ConfigGroup, {
  ConfigRow,
  ConfigStatusRow,
  ConfigBoolRow,
  useDurationFormat,
} from '@/components/common/config/ConfigGroup';
import useConfigSnapshot from '@/components/common/config/useConfigSnapshot';
import settingsApi from '@/api/settingsApi';
import { formatFileSize } from '@/utils/formatting';
import ScriptBench from './ScriptBench';
import SavedScriptBench from './SavedScriptBench';
import ScriptSchedules from './ScriptSchedules';

/**
 * Снимок kb.script.* — песочницы, в которой выполняется инструмент runScript, —
 * и пробный запуск под ним. Сам снимок read-only, источник истины application.yaml;
 * меняется только текст скрипта в стенде, и тот никуда не сохраняется.
 */
const ScriptsSettings = () => {
  const { t } = useTranslation('settings');
  const { data: config, error } = useConfigSnapshot(settingsApi.getAiConfig);

  return (
    <ConfigGroup title={t('scripts.title')} subtitle={t('scripts.subtitle')} data={config} error={error}>
      <ScriptsSections config={config} />
    </ConfigGroup>
  );
};

const ScriptsSections = ({ config }) => {
  const { t } = useTranslation('settings');
  const duration = useDurationFormat();
  const script = config.script;
  const { limits } = script;

  return (
    <>
      {/* ── Состояние ── */}
      <SettingsSection label={t('scripts.status.label')}>
        {/* Строка отвечает на вопрос «есть ли runScript у модели», а не «что стоит в конфиге»:
            флаг — то, что прочитал процесс, active — то, что собралось из него. */}
        <ConfigStatusRow label={t('scripts.status.enabled')} on={script.active} />
        <ConfigBoolRow label={t('scripts.status.editEnabled')} value={script.editEnabled} />
        <ConfigBoolRow label={t('scripts.status.editActive')} value={script.editActive} />
        {/* Запуск вложений — не право модели поверх скриптов, а происхождение кода:
            сама песочница та же, и такой прогон всегда read-only. */}
        <ConfigBoolRow label={t('scripts.status.attachmentRun')} value={script.attachmentRun} />
        <ConfigBoolRow label={t('scripts.status.attachmentEdit')} value={script.attachmentEdit} />
        {!script.enabled && <p className="config-note">{t('scripts.status.disabledNote')}</p>}
        {/* Правка из скриптов требует трёх согласий (ScriptEditPolicy), поэтому
            разрешение в конфиге и фактическая привязка методов записи — разные строки. */}
        {script.editEnabled && !script.editActive && <p className="config-note">{t('scripts.status.readOnlyNote')}</p>}
      </SettingsSection>

      {/* ── Время ── */}
      <SettingsSection label={t('scripts.time.label')}>
        <ConfigRow label={t('scripts.time.timeout')} value={duration(script.timeoutSeconds)} />
        <ConfigRow label={t('scripts.time.maxTimeout')} value={duration(script.maxTimeoutSeconds)} />
        <ConfigRow
          label={t('scripts.time.cancelPoll')}
          value={t('scripts.time.msValue', { ms: script.cancelPollMillis })}
        />
      </SettingsSection>

      {/* ── Бюджеты одного прогона ── */}
      <SettingsSection label={t('scripts.limits.label')}>
        <ConfigRow label={t('scripts.limits.maxFilesRead')} value={limits.maxFilesRead.toLocaleString()} />
        <ConfigRow label={t('scripts.limits.maxBytesRead')} value={formatFileSize(limits.maxBytesRead)} />
        <ConfigRow label={t('scripts.limits.maxCalls')} value={limits.maxCalls.toLocaleString()} />
        <ConfigRow label={t('scripts.limits.maxLogChars')} value={limits.maxLogChars.toLocaleString()} />
        <ConfigRow label={t('scripts.limits.maxResultChars')} value={limits.maxResultChars.toLocaleString()} />
        <ConfigRow label={t('scripts.limits.maxEditedFiles')} value={limits.maxEditedFiles.toLocaleString()} />
        <ConfigRow label={t('scripts.limits.maxEditedBytes')} value={formatFileSize(limits.maxEditedBytes)} />
        <p className="config-note">{t('scripts.limits.note')}</p>
      </SettingsSection>

      {/* ── Пробный запуск ── */}
      <ScriptBench enabled={script.enabled} />

      {/* ── Скрипты, объявленные репозиторием ── */}
      <SavedScriptBench enabled={script.enabled} />

      {/* ── Расписание: решение развёртки, а не репозитория ── */}
      {script.schedules > 0 && <ScriptSchedules />}
    </>
  );
};

export default ScriptsSettings;
