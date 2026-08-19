import { useCallback, useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import useSearchDropdown from './useSearchDropdown';
import { IconSearch, IconX } from '@/icons/index';
import './panelSearch.css';

/**
 * Поиск над списком/деревом левой панели: кнопка-триггер → поле ввода →
 * плавающий список результатов (портал, позиционируется под полем).
 *
 * Один компонент на все разделы. Раньше это были две копии — ChatSearch и
 * FileSearch: логика у обеих и так общая (useSearchDropdown), а расходились
 * форма триггера, высота поля, шрифт, размеры выпадающего списка и ключи i18n.
 * Раздел теперь передаёт только «чем искать» (search) и «как описать строку»
 * (describeItem); разметку и вид держит этот компонент.
 *
 * props:
 *   label        — подпись на кнопке-триггере («Поиск по чатам», «Найти файл»)
 *   placeholder  — плейсхолдер поля ввода
 *   hint         — подсказка в пустом списке (до ввода запроса)
 *   search       — (query, signal) => Promise<item[]>
 *   describeItem — (item, query) => { icon, title, subtitle, badge, multiline }
 *                  title/subtitle могут быть готовыми нодами (с подсветкой).
 *                  multiline=true разрешает подзаголовку две строки (сниппет
 *                  сообщения), по умолчанию — одна с многоточием.
 *   getKey       — (item) => React key
 *   onSelect     — (item, query) => void; закрытие поиска берёт на себя компонент
 *   debounceMs   — задержка перед запросом (по умолчанию из useSearchDropdown)
 *   minWidth     — минимальная ширина выпадающего списка (он шире панели)
 */
const PanelSearch = ({
  label,
  placeholder,
  hint,
  search,
  describeItem,
  getKey,
  onSelect,
  debounceMs,
  minWidth = 340,
}) => {
  const { t } = useTranslation();
  const {
    open,
    query,
    results,
    loading,
    idx,
    anchorRect,
    wrapRef,
    inputRef,
    listRef,
    portalRef,
    setIdx,
    openSearch,
    close,
    handleChange,
    handleKeyDown,
  } = useSearchDropdown(search, debounceMs ? { debounceMs } : undefined);

  const choose = useCallback(
    (item) => {
      onSelect(item, query.trim());
      close();
    },
    [onSelect, query, close],
  );

  // Поле поиска исчезает при закрытии — вместе с ним пропадал бы и фокус (Escape
  // отправлял его в body, и Tab начинал обход страницы заново). Возвращаем его на
  // кнопку-триггер, но только если фокус действительно осиротел: после выбора
  // результата его мог забрать открытый чат или документ, и отнимать нельзя.
  const triggerRef = useRef(null);
  const wasOpen = useRef(false);
  useEffect(() => {
    if (wasOpen.current && !open && document.activeElement === document.body) {
      triggerRef.current?.focus();
    }
    wasOpen.current = open;
  }, [open]);

  const trimmed = query.trim();

  // Поле + плавающий список — это combobox с listbox: поле остаётся
  // сфокусированным, а «текущий результат» скринридеру называет
  // aria-activedescendant. Список висит в портале на body, поэтому связь между
  // ними держится только на id — их и раздаём отсюда.
  const baseId = useId();
  const listId = `${baseId}list`;
  const optionId = (i) => `${baseId}opt${i}`;
  const hasResults = results.length > 0;

  return (
    <div className="panel-search" ref={wrapRef}>
      {open ? (
        <div className="panel-search__field">
          <span className="panel-search__field-icon">
            <IconSearch size={13} />
          </span>
          <input
            ref={inputRef}
            type="text"
            role="combobox"
            aria-label={label}
            aria-autocomplete="list"
            aria-expanded={hasResults}
            aria-controls={hasResults ? listId : undefined}
            aria-activedescendant={hasResults ? optionId(idx) : undefined}
            className="panel-search__input"
            placeholder={placeholder}
            value={query}
            onChange={handleChange}
            onKeyDown={(e) => handleKeyDown(e, choose)}
          />
          <button
            type="button"
            className="icon-btn panel-search__close"
            title={t('panelSearch.close')}
            aria-label={t('panelSearch.close')}
            onClick={close}
          >
            <IconX size={11} />
          </button>
        </div>
      ) : (
        <button
          type="button"
          ref={triggerRef}
          className="btn btn--ghost btn--sm panel-search__trigger"
          onClick={openSearch}
          title={label}
        >
          <IconSearch size={14} />
          <span className="panel-search__trigger-label">{label}</span>
        </button>
      )}

      {open &&
        anchorRect &&
        createPortal(
          <div
            ref={portalRef}
            className="panel-search__dropdown"
            style={{ top: anchorRect.bottom + 6, left: anchorRect.left, width: Math.max(anchorRect.width, minWidth) }}
          >
            {loading && <div className="panel-search__msg">{t('panelSearch.searching')}</div>}
            {!loading && trimmed.length >= 1 && results.length === 0 && (
              <div className="panel-search__msg">{t('panelSearch.empty')}</div>
            )}
            {!loading && trimmed.length === 0 && <div className="panel-search__msg">{hint}</div>}
            {hasResults && (
              <div className="panel-search__list" role="listbox" id={listId} aria-label={label} ref={listRef}>
                {results.map((item, i) => {
                  const { icon, title, subtitle, badge, multiline } = describeItem(item, query);
                  return (
                    <button
                      key={getKey(item)}
                      type="button"
                      role="option"
                      id={optionId(i)}
                      aria-selected={i === idx}
                      // Фокус остаётся в поле ввода — по списку ходят стрелками,
                      // а не табом (иначе Tab уводил бы в конец body, где портал).
                      tabIndex={-1}
                      className={`panel-search__item${i === idx ? ' panel-search__item--selected' : ''}`}
                      onMouseEnter={() => setIdx(i)}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        choose(item);
                      }}
                    >
                      {icon && <span className="panel-search__item-icon">{icon}</span>}
                      <span className="panel-search__item-body">
                        <span className="panel-search__item-title">{title}</span>
                        {subtitle && (
                          <span
                            className={`panel-search__item-sub${multiline ? ' panel-search__item-sub--multiline' : ''}`}
                          >
                            {subtitle}
                          </span>
                        )}
                      </span>
                      {badge > 0 && <span className="panel-search__item-badge">{badge}</span>}
                    </button>
                  );
                })}
              </div>
            )}
          </div>,
          document.body,
        )}
    </div>
  );
};

export default PanelSearch;
