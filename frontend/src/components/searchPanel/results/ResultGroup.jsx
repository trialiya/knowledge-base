import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** Сколько совпадений показывать в свёрнутой карточке. */
const PREVIEW_ROWS = 5;

/** Клик, который надо отдать браузеру: модификатор или не левая кнопка. */
const isBrowserClick = (e) => e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0;

/**
 * Первые PREVIEW_ROWS совпадений вместе с подзаголовками, под которыми они
 * стоят. Подзаголовок в счёт не идёт и не остаётся висеть последним без своих
 * строк.
 */
function previewRows(rows) {
  const shown = [];
  let lines = 0;
  for (const row of rows) {
    if (lines === PREVIEW_ROWS) break;
    if (!row.heading) lines += 1;
    shown.push(row);
  }
  return shown;
}

/**
 * Карточка одной найденной сущности: файл, документ или чат.
 *
 * Группировка по сущности — главное отличие этого поиска от списка совпадений:
 * файл с двадцатью вхождениями остаётся одной строкой результата, а вхождения
 * лежат внутри. Поэтому длинный хвост сворачивается в «ещё N», а не растягивает
 * список так, что второй файл уходит за экран.
 *
 * Заголовок — настоящий `<a href>`: средняя кнопка мыши и Ctrl/Cmd-клик обязаны
 * открывать новую вкладку по актуальному адресу, а обычный клик — оставаться
 * SPA-переходом. Строка с собственным адресом (раздел документа) — такая же
 * ссылка.
 *
 * props:
 *   icon        — узел слева от названия
 *   title       — название сущности (узел: может быть с подсветкой)
 *   href        — канонический адрес сущности
 *   onOpen      — SPA-переход по обычному левому клику
 *   meta        — правый угол шапки (дата, число совпадений)
 *   subtitle    — строка под шапкой (хлебные крошки, путь)
 *   rows        — [{ key, node, heading?, href?, onOpen? }] совпадения внутри
 *                 сущности; `heading` — подзаголовок группы совпадений (раздел
 *                 документа), в счёт «ещё N» не идёт; `href` + `onOpen` делают
 *                 строку ссылкой
 */
const ResultGroup = ({ icon, title, href, onOpen, meta, subtitle, rows }) => {
  const { t } = useTranslation('search');
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? rows : previewRows(rows);
  const hidden = rows.filter((row) => !row.heading).length - shown.filter((row) => !row.heading).length;

  const open = (e, action) => {
    if (isBrowserClick(e)) return;
    e.preventDefault();
    action();
  };

  return (
    <article className="search-group">
      <header className="search-group__head">
        <span className="search-group__icon">{icon}</span>
        <a className="search-group__title" href={href} onClick={(e) => open(e, onOpen)}>
          {title}
        </a>
        {meta && <span className="search-group__meta">{meta}</span>}
      </header>
      {subtitle && <div className="search-group__subtitle">{subtitle}</div>}
      <div className="search-group__rows">
        {shown.map((row) => {
          const className = row.heading ? 'search-group__section' : 'search-group__row';
          return row.href ? (
            <a key={row.key} className={className} href={row.href} onClick={(e) => open(e, row.onOpen)}>
              {row.node}
            </a>
          ) : (
            <div key={row.key} className={className}>
              {row.node}
            </div>
          );
        })}
      </div>
      {(hidden > 0 || expanded) && (
        <button type="button" className="search-group__more" onClick={() => setExpanded(!expanded)}>
          {expanded ? t('group.collapse') : t('group.more', { count: hidden })}
        </button>
      )}
    </article>
  );
};

export default ResultGroup;
