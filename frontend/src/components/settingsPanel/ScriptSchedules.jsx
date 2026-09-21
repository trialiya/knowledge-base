import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import { ConfigRow } from '@/components/common/config/ConfigGroup';
import settingsApi from '@/api/settingsApi';

// Что развёртка запускает сама по расписанию (kb.script.schedules) и чем кончился
// последний прогон каждого. Только чтение: расписание — конфигурация, а её здесь
// не правят. Истории прогонов тут нет и не обещается — в памяти живёт последний,
// остальное в логе (см. ScheduledScriptService).

/** Итог последнего прогона одной строкой: когда, чем кончился, сколько занял. */
const lastRunText = (t, lastRun, language) => {
  if (!lastRun) return t('scripts.schedules.never');
  const at = new Date(lastRun.at).toLocaleString(language);
  return lastRun.ok
    ? t('scripts.schedules.okAt', { at, ms: lastRun.elapsedMs })
    : t('scripts.schedules.failedAt', { at, error: lastRun.error ?? '' });
};

const ScriptSchedules = () => {
  const { t, i18n } = useTranslation('settings');
  const [schedules, setSchedules] = useState([]);

  useEffect(() => {
    let cancelled = false;
    settingsApi
      .listScriptSchedules()
      .then((data) => {
        if (!cancelled) setSchedules(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (!cancelled) setSchedules([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (schedules.length === 0) return null;

  return (
    <SettingsSection label={t('scripts.schedules.label')}>
      <p className="config-note">{t('scripts.schedules.note')}</p>
      {schedules.map((schedule) => (
        <ConfigRow
          key={schedule.name}
          label={`${schedule.name} · ${schedule.cron}`}
          value={lastRunText(t, schedule.lastRun, i18n.language)}
        />
      ))}
    </SettingsSection>
  );
};

export default ScriptSchedules;
