import { useTranslation } from 'react-i18next';
import { formatFileSize } from '@/utils/formatting';

// Ответ прогона скрипта, общий для обоих стендов: свободного текста и запуска
// сохранённого скрипта по имени. Форма ответа у них одна — ScriptResult
// бэкенда, — и показывать её двумя копиями значило бы дать им разойтись.

/** Ответ прогона: счётчики, ошибка (если была), журнал kb.log и возвращённое значение. */
const ScriptRunResult = ({ result }) => {
  const { t } = useTranslation('settings');
  const { stats, error, log, value, filesRead } = result;

  return (
    <div className="script-result">
      {/* Параметры названы не count: с ним i18next включает плюрализацию, а
          здесь это просто числа в строке счётчиков. */}
      <div className="script-result__stats">
        <span>{t('scripts.bench.statsFiles', { files: stats.filesRead })}</span>
        <span>{t('scripts.bench.statsBytes', { size: formatFileSize(stats.bytesRead) })}</span>
        <span>{t('scripts.bench.statsCalls', { calls: stats.calls })}</span>
        <span>{t('scripts.bench.statsElapsed', { ms: stats.elapsedMs })}</span>
      </div>

      {error && (
        <div className="script-result__error">
          <span className="set-op__badge set-op__badge--error">{error.kind}</span>
          <span>
            {error.message}
            {error.line != null && ` (${t('scripts.bench.errorLine', { line: error.line })})`}
          </span>
        </div>
      )}

      {log?.length > 0 && (
        <>
          <div className="script-result__label">{t('scripts.bench.log')}</div>
          <pre className="script-result__pre">{log.join('\n')}</pre>
        </>
      )}

      {!error && (
        <>
          <div className="script-result__label">{t('scripts.bench.value')}</div>
          <pre className="script-result__pre">{JSON.stringify(value, null, 2)}</pre>
        </>
      )}

      {filesRead?.length > 0 && (
        <div className="script-result__files">{t('scripts.bench.filesRead', { files: filesRead.join(', ') })}</div>
      )}
    </div>
  );
};

export default ScriptRunResult;
