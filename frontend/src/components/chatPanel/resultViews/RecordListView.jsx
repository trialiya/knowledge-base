import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronDown } from '../../../icons';
import { formatFileSize } from '../../../utils/formatting';

// Режим «Обзор» для формы «список однотипных записей»: строка на запись,
// полный набор полей — по развороту.
//
// Разбор ответа — в recordList.js; сюда приходят уже готовые записи.

/** Сколько строк показываем до нажатия «показать ещё». */
const ROW_CAP = 200;

const SIZE_KEYS = new Set(['fileSize', 'sizeBytes', 'size']);

// Дата опознаётся по виду значения, а не по имени поля: у MCP-инструментов
// поле может называться как угодно, а ISO-8601 остаётся ISO-8601.
const ISO_DATE = /^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}|$)/;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

/** Значение поля → строка. Вложенное — свёрнуто, а не спрятано. */
const formatValue = (key, value, locale) => {
  if (Array.isArray(value)) {
    // Хлебные крошки (`parentList`) и подобные списки объектов читаются по
    // названиям, а не по JSON.
    return value
      .map((item) => (isPlainObject(item) ? item.title ?? item.name ?? JSON.stringify(item) : String(item)))
      .join(' / ');
  }
  if (isPlainObject(value)) return JSON.stringify(value);
  if (typeof value === 'number' && SIZE_KEYS.has(key)) return formatFileSize(value);
  if (typeof value === 'string' && ISO_DATE.test(value)) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) return date.toLocaleString(locale);
  }
  return String(value);
};

const FieldLabel = ({ name }) => {
  const { t } = useTranslation('chat');
  return <>{t(`toolCall.detail.fact.${name}`, { defaultValue: name })}</>;
};

/** Одна запись: строка-заголовок, под ней — все поля. */
const Record = ({ record, open, onToggle }) => {
  const { i18n } = useTranslation('chat');

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
        {record.meta.map(({ key, value }) => (
          <span key={key} className="tool-records__chip">
            {formatValue(key, value, i18n.language)}
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
              <dd className="tool-records__field-value">{formatValue(key, value, i18n.language)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
};

const RecordListView = ({ data: records }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState({});
  const [expanded, setExpanded] = useState(false);

  const shown = expanded ? records.length : Math.min(records.length, ROW_CAP);
  const hidden = records.length - shown;
  const toggle = (key) => setOpen((prev) => ({ ...prev, [key]: !prev[key] }));

  return (
    <div className="tool-records">
      <div className="tool-records__count">{t('toolCall.detail.records.count', { count: records.length })}</div>

      {records.slice(0, shown).map((record) => (
        <Record key={record.key} record={record} open={!!open[record.key]} onToggle={() => toggle(record.key)} />
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
