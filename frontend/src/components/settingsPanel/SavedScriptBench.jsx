import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import settingsApi from '@/api/settingsApi';
import ScriptRunResult from './ScriptRunResult';

// Запуск скрипта, объявленного репозиторием (kb.projects[].scripts-manifest), —
// тот же движок и те же бюджеты, что у модели, но по коду из дерева и всегда
// read-only, как весь стенд. Это рабочий цикл автора скрипта: правка файла,
// прогон, ошибка со строкой — без похода в чат и надежды, что модель напишет
// тот скрипт, который имели в виду.
//
// Объявленные параметры превращаются в поля формы: их типы и обязательность
// знает манифест, и повторять их здесь вторым списком незачем.

/** Пустая строка — это «не передавали», а не пустое значение аргумента. */
const collectArgs = (params, values) => {
  const args = {};
  for (const param of params) {
    const raw = values[param.name];
    if (raw === undefined || raw === '') continue;
    args[param.name] = raw;
  }
  return args;
};

const SavedScriptBench = ({ enabled }) => {
  const { t } = useTranslation('settings');
  const [catalog, setCatalog] = useState(null);
  const [selected, setSelected] = useState('');
  const [values, setValues] = useState({});
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    let cancelled = false;
    settingsApi
      .listSavedScripts()
      .then((data) => {
        if (!cancelled) setCatalog(data);
      })
      .catch(() => {
        if (!cancelled) setCatalog({ scripts: [] });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const scripts = catalog?.scripts ?? [];
  const script = scripts.find((s) => s.name === selected) ?? null;

  const run = async () => {
    if (!script) return;
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      // Неудача самого скрипта приходит успешным ответом с полем error; HTTP-ошибка
      // означает отказ эндпоинта — выключённые скрипты, неизвестное имя, не тот аргумент.
      setResult(await settingsApi.runSavedScript(script.name, collectArgs(script.params, values)));
    } catch (e) {
      setError(e.status === 409 ? t('scripts.bench.disabledError') : e.message || t('scripts.bench.requestError'));
    } finally {
      setRunning(false);
    }
  };

  if (catalog && scripts.length === 0) {
    return (
      <SettingsSection label={t('scripts.saved.label')}>
        <p className="config-note">{t('scripts.saved.emptyNote')}</p>
      </SettingsSection>
    );
  }

  return (
    <SettingsSection label={t('scripts.saved.label')}>
      <p className="config-note">{t('scripts.saved.note', { project: catalog?.label ?? '' })}</p>

      <select
        className="set-select"
        value={selected}
        aria-label={t('scripts.saved.pick')}
        onChange={(e) => {
          setSelected(e.target.value);
          setValues({});
          setResult(null);
          setError(null);
        }}
      >
        <option value="">{t('scripts.saved.pick')}</option>
        {scripts.map((item) => (
          <option key={item.name} value={item.name}>
            {item.name}
          </option>
        ))}
      </select>

      {script && (
        <div className="saved-script">
          <p className="saved-script__desc">{script.desc}</p>
          <p className="saved-script__file">{script.file}</p>
          {script.write && <p className="config-note">{t('scripts.saved.writeNote')}</p>}

          {script.params.map((param) => (
            <label key={param.name} className="saved-script__param">
              <span className="saved-script__param-name">
                {param.name}
                {param.required && <span className="saved-script__required"> *</span>}
                <span className="saved-script__param-type"> {param.type}</span>
              </span>
              <input
                className="set-input"
                value={values[param.name] ?? ''}
                placeholder={param.defaultValue == null ? param.desc : String(param.defaultValue)}
                onChange={(e) => setValues((prev) => ({ ...prev, [param.name]: e.target.value }))}
              />
            </label>
          ))}

          <div className="script-bench__actions">
            <button className="btn btn--primary" onClick={run} disabled={running || !enabled || script.write}>
              {running ? t('scripts.bench.running') : t('scripts.bench.run')}
            </button>
            {!enabled && <span className="script-bench__hint">{t('scripts.bench.disabledHint')}</span>}
          </div>
        </div>
      )}

      {error && <p className="phrase-error">{error}</p>}
      {result && <ScriptRunResult result={result} />}
    </SettingsSection>
  );
};

export default SavedScriptBench;
