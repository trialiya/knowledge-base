import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '@/icons/index';
import { formatFieldValue } from './fieldValue';
import DiffResultView from './DiffResultView';

// Режим «Обзор» для формы «прогон скрипта»: плитки статистики, ошибка, лог,
// прочитанные пути и правки — вместо одной простыни, в которой всё это лежит
// вперемешку с экранированными патчами.
//
// Разбор ответа — в scriptRun.js; сюда приходят уже готовые данные.

/** Сколько строк лога показываем до нажатия «показать ещё». */
const LOG_CAP = 200;

// Короткий лог раскрыт сразу: до этой длины он читается как часть страницы, а
// не как отдельный документ.
const LOG_OPEN_LINES = 20;

/** Сворачиваемый раздел: заголовок со счётчиком, под ним — содержимое. */
const Panel = ({ label, count, defaultOpen, children }) => {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <section className="tool-script__panel">
      <button type="button" className="tool-script__panel-head" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className={`tool-script__chevron${open ? ' tool-script__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        <span className="tool-script__panel-label">{label}</span>
        <span className="tool-script__panel-count">{count}</span>
      </button>
      {open && children}
    </section>
  );
};

/**
 * Лог прогона. Разворот держит не он, а вызывающий: свёрнутая секция уносит
 * блок с экрана, и уже сделанное «показать ещё» ушло бы вместе с ним.
 */
const LogBlock = ({ lines, expanded, onExpand }) => {
  const { t } = useTranslation('chat');
  const shown = expanded ? lines.length : Math.min(lines.length, LOG_CAP);
  const hidden = lines.length - shown;

  return (
    <>
      <pre className="tool-script__log">{lines.slice(0, shown).join('\n')}</pre>
      {hidden > 0 && (
        <button type="button" className="btn btn--ghost btn--sm" onClick={onExpand}>
          {t('toolCall.detail.showAll', { count: hidden })}
        </button>
      )}
    </>
  );
};

const ScriptRunView = ({ data }) => {
  const { t, i18n } = useTranslation('chat');
  const [logExpanded, setLogExpanded] = useState(false);

  return (
    <div className="tool-script">
      <div className="tool-script__stats">
        {data.project && (
          <div className="tool-script__stat">
            <span className="tool-script__stat-value">{data.project}</span>
            <span className="tool-script__stat-label">{t('toolCall.detail.fact.project')}</span>
          </div>
        )}
        {data.stats.map(({ key, value }) => (
          <div key={key} className="tool-script__stat">
            <span className="tool-script__stat-value">{formatFieldValue(key, value, i18n.language)}</span>
            <span className="tool-script__stat-label">
              {t(`toolCall.detail.script.stat.${key}`, { defaultValue: key })}
            </span>
          </div>
        ))}
      </div>

      {data.error && (
        <div className="tool-script__error">
          <span className="tool-script__error-kind">
            {t(`toolCall.detail.script.error.${data.error.kind}`, { defaultValue: data.error.kind })}
          </span>
          {data.error.line != null && (
            <span className="tool-script__error-line">
              {t('toolCall.detail.script.line', { line: data.error.line })}
            </span>
          )}
          {data.error.message && <span className="tool-script__error-message">{data.error.message}</span>}
        </div>
      )}

      {data.value !== null && (
        <section className="tool-script__panel">
          <div className="tool-script__section-label">{t('toolCall.detail.script.value')}</div>
          <pre className="tool-script__value">{data.value}</pre>
        </section>
      )}

      {data.log.length > 0 && (
        <Panel
          label={t('toolCall.detail.script.log')}
          count={t('toolCall.detail.script.logLines', { count: data.log.length })}
          // Упавший прогон — тот случай, когда лог и есть диагноз: разворачивать
          // его руками пришлось бы всегда.
          defaultOpen={!!data.error || data.log.length <= LOG_OPEN_LINES}
        >
          <LogBlock lines={data.log} expanded={logExpanded} onExpand={() => setLogExpanded(true)} />
        </Panel>
      )}

      {data.filesRead.length > 0 && (
        <Panel
          label={t('toolCall.detail.script.filesRead')}
          count={t('toolCall.detail.script.pathCount', { count: data.filesRead.length })}
          defaultOpen={false}
        >
          <ul className="tool-script__paths">
            {data.filesRead.map((path) => (
              <li key={path} className="tool-script__path">
                {path}
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {data.edits && (
        <section className="tool-script__panel">
          <div className="tool-script__section-label">{t('toolCall.detail.script.edits')}</div>
          <DiffResultView data={data.edits} />
        </section>
      )}
    </div>
  );
};

export default ScriptRunView;
