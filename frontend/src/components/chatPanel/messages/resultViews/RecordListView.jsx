import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '@/icons/index';
import { formatFieldValue } from './fieldValue';
import ResultSummary, { useExpandAll } from './resultSummary';

// Режим «Обзор» для формы «список однотипных записей»: строка на запись,
// полный набор полей — по развороту.
//
// Разбор ответа — в recordList.js; сюда приходят уже готовые записи.

/** Сколько строк показываем до нажатия «показать ещё». */
const ROW_CAP = 200;

const FieldLabel = ({ name }) => {
  const { t } = useTranslation('chat');
  return <>{t(`toolCall.detail.fact.${name}`, { defaultValue: name })}</>;
};

/** Одна запись: строка-заголовок, под ней — все поля. */
const Record = ({ record, open, onToggle }) => {
  const { t, i18n } = useTranslation('chat');

  return (
    <div className="tool-records__item">
      <button type="button" className="tool-records__head" onClick={onToggle} aria-expanded={open}>
        <span className={`tool-records__chevron${open ? ' tool-records__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        <span className="tool-records__text">
          <span className="tool-records__title" title={record.title}>
            {record.title}
          </span>
          {record.subtitle && <span className="tool-records__subtitle">{record.subtitle}</span>}
        </span>
        {/* Подпись поля — в title: в строке чипу места нет, а без пояснения
            «text/plain» и «2.0 KB» читаются и так. */}
        {record.meta.map(({ key, value }) => (
          <span
            key={key}
            className="tool-records__chip"
            title={t(`toolCall.detail.fact.${key}`, { defaultValue: key })}
          >
            {formatFieldValue(key, value, i18n.language)}
          </span>
        ))}
      </button>

      {open && (
        <dl className="tool-records__fields">
          {record.fields.map(({ key, value }) => (
            <div key={key} className="tool-records__field">
              <dt className="tool-records__field-key">
                <FieldLabel name={key} />
              </dt>
              <dd className="tool-records__field-value">{formatFieldValue(key, value, i18n.language)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
};

const RecordListView = ({ data: records }) => {
  const { t } = useTranslation('chat');
  const [expanded, setExpanded] = useState(false);
  // Записи свёрнуты: список — чтобы просмотреть глазами, а не вчитаться.
  const expand = useExpandAll(
    records.map((record) => record.key),
    {},
  );

  const shown = expanded ? records.length : Math.min(records.length, ROW_CAP);
  const hidden = records.length - shown;

  return (
    <div className="tool-records">
      <ResultSummary expand={records.length > 1 ? expand : null}>
        {t('toolCall.detail.records.count', { count: records.length })}
      </ResultSummary>

      {records.slice(0, shown).map((record) => (
        <Record
          key={record.key}
          record={record}
          open={expand.isOpen(record.key)}
          onToggle={() => expand.toggle(record.key)}
        />
      ))}

      {hidden > 0 && (
        <button type="button" className="btn btn--ghost btn--sm" onClick={() => setExpanded(true)}>
          {t('toolCall.detail.records.showAll', { count: hidden })}
        </button>
      )}
    </div>
  );
};

export default RecordListView;
