import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import settingsApi from '@/api/settingsApi';
import ScriptRunResult from './ScriptRunResult';
import SCRIPT_EXAMPLE from './scriptExample';

/**
 * Пробный запуск скрипта: тот же движок и те же бюджеты, что у инструмента
 * runScript, но скрипт пишет человек и результат показывается ему, а не модели.
 * Нужен, чтобы проверить сам механизм и посмотреть, что на самом деле
 * возвращает kb.*, не устраивая ради этого диалог с моделью.
 *
 * Запуск всегда read-only — kb.edit/kb.create в песочницу не привязываются,
 * как бы ни был выставлен kb.script.edit-enabled (см. ScriptTestController).
 * Правки делаются в чате, где диффы показываются и привязаны к сообщению.
 *
 * props:
 *   enabled — kb.script.enabled: с выключенным инструментом эндпоинт отвечает
 *             409, поэтому кнопка заблокирована, а поле остаётся — пример
 *             читается и без запуска
 */
const ScriptBench = ({ enabled }) => {
  const { t } = useTranslation('settings');
  const [script, setScript] = useState(SCRIPT_EXAMPLE);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [running, setRunning] = useState(false);

  const run = async () => {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      // Неудача скрипта приходит успешным ответом с полем error — это результат
      // прогона, а не сбой запроса. HTTP-ошибка означает отказ самого эндпоинта.
      setResult(await settingsApi.runScript(script, null));
    } catch (e) {
      setError(e.status === 409 ? t('scripts.bench.disabledError') : t('scripts.bench.requestError'));
    } finally {
      setRunning(false);
    }
  };

  return (
    <SettingsSection label={t('scripts.bench.label')}>
      <p className="config-note">{t('scripts.bench.note')}</p>
      <textarea
        className="set-textarea script-bench__editor"
        value={script}
        spellCheck={false}
        onChange={(e) => setScript(e.target.value)}
        aria-label={t('scripts.bench.editorLabel')}
      />
      <div className="script-bench__actions">
        <button className="btn btn--primary" onClick={run} disabled={running || !enabled || !script.trim()}>
          {running ? t('scripts.bench.running') : t('scripts.bench.run')}
        </button>
        <button
          className="btn btn--ghost"
          onClick={() => setScript(SCRIPT_EXAMPLE)}
          disabled={running || script === SCRIPT_EXAMPLE}
        >
          {t('scripts.bench.reset')}
        </button>
        {!enabled && <span className="script-bench__hint">{t('scripts.bench.disabledHint')}</span>}
      </div>

      {error && <p className="phrase-error">{error}</p>}
      {result && <ScriptRunResult result={result} />}
    </SettingsSection>
  );
};

export default ScriptBench;
