import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** Сколько совпадений показывать в свёрнутой карточке. */
const PREVIEW_ROWS = 5;

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
 * SPA-переходом.
 *
 * props:
 *   icon        — узел слева от названия
 *   title       — название сущности (узел: может быть с подсветкой)
 *   href        — канонический адрес сущности
 *   onOpen      — SPA-переход по обычному левому клику
 *   meta        — правый угол шапки (дата, число совпадений)
 *   subtitle    — строка под шапкой (хлебные крошки, путь)
 *   rows        — [{ key, node }] совпадения внутри сущности
 */
const ResultGroup = ({ icon, title, href, onOpen, meta, subtitle, rows }) => {
  const { t } = useTranslation('search');
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? rows : rows.slice(0, PREVIEW_ROWS);
  const hidden = rows.length - shown.length;

  return (
    <article className="search-group">
      <header className="search-group__head">
        <span className="search-group__icon">{icon}</span>
        <a
          className="search-group__title"
          href={href}
          onClick={(e) => {
            // Клик с модификатором или не левой кнопкой — браузеру: он откроет
            // ссылку в новой вкладке. Средняя кнопка сюда не приходит вовсе.
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
            e.preventDefault();
            onOpen();
          }}
        >
          {title}
        </a>
        {meta && <span className="search-group__meta">{meta}</span>}
      </header>
      {subtitle && <div className="search-group__subtitle">{subtitle}</div>}
      <div className="search-group__rows">
        {shown.map((row) => (
          <div key={row.key} className="search-group__row">
            {row.node}
          </div>
        ))}
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
