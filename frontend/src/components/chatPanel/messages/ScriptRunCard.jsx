import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconCheck, IconChevronDown, IconChevronRight, IconX } from '@/icons/index';

// Плашка прогона, который человек запустил сам командой `/script`: что запускали,
// что вернулось и какие файлы сдвинулись. Пузырём это быть не может — человек
// ничего не написал, — а вида вызова инструмента здесь тоже нет: вызывала не
// модель, и деталей вызова в истории под этот ряд не заведено.
//
// Возврат скрипта раскрыт сразу, а журнал — нет: за значением команду и давали,
// журнал читают, когда значение не сошлось. Упавший прогон — наоборот: там
// журнал и есть диагноз (то же правило, что у ScriptRunView в ленте модели).

/** Возврат скрипта строкой: объект — JSON с отступами, строка — как есть. */
const valueText = (value) => {
  if (value === null || value === undefined) return null;
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
};

const ScriptRunCard = ({ event }) => {
  const { t } = useTranslation('chat');
  const [logOpen, setLogOpen] = useState(!event.ok);
  const value = valueText(event.value);
  const log = event.output?.trim() ?? '';

  return (
    <div className={`script-run${event.ok ? '' : ' script-run--failed'}`}>
      <div className="script-run__head">
        <span className="script-run__icon" aria-hidden="true">
          {event.ok ? <IconCheck size={12} /> : <IconX size={12} />}
        </span>
        <span className="script-run__name">{event.script}</span>
        {event.path && <span className="script-run__path">{event.path}</span>}
        {event.resultId && (
          <span className="script-run__result" title={t('toolCall.detail.script.resultHint', { id: event.resultId })}>
            {t('scriptRun.result', { id: event.resultId })}
          </span>
        )}
        {event.stats && (
          <span className="script-run__stats">
            {t('scriptRun.stats', { files: event.stats.filesRead, ms: event.stats.elapsedMs })}
          </span>
        )}
      </div>

      {event.error && (
        <div className="script-run__error">
          <span className="script-run__error-kind">
            {t(`toolCall.detail.script.error.${event.error.kind}`, { defaultValue: event.error.kind })}
          </span>
          {event.error.line != null && <span>{t('toolCall.detail.script.line', { line: event.error.line })}</span>}
          {event.error.message && <span className="script-run__error-message">{event.error.message}</span>}
        </div>
      )}

      {value !== null && <pre className="script-run__value">{value}</pre>}

      {event.edited?.length > 0 && (
        <div className="script-run__edited">
          {t('scriptRun.edited', { count: event.edited.length })}
          <span className="script-run__paths">{event.edited.join(', ')}</span>
        </div>
      )}

      {log && (
        <>
          <button
            type="button"
            className="script-run__log-toggle"
            onClick={() => setLogOpen((v) => !v)}
            aria-expanded={logOpen}
          >
            {logOpen ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
            {t('scriptRun.log')}
          </button>
          {logOpen && <pre className="script-run__log">{log}</pre>}
        </>
      )}
    </div>
  );
};

export default ScriptRunCard;
