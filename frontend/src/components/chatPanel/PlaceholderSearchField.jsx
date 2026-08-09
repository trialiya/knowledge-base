import { useTranslation } from 'react-i18next';
import useSearchDropdown from '../../hooks/useSearchDropdown';
import highlightMatch from '../common/highlightMatch';

/**
 * Комбобокс для плейсхолдеров-указателей (file / document / commit).
 *
 * Значением поля становится сам выбранный элемент, а не набранный текст: в токен
 * его превращает диалог (`spec.toValue`) уже при подстановке. Пока из списка
 * ничего не выбрано, поле считается пустым — свободный текст указателем не
 * является, иначе в сообщение уехал бы путь, которого в репозитории нет.
 *
 * Props:
 *   spec        — запись из PLACEHOLDER_FIELDS (search + describe)
 *   selected    — выбранный элемент или null
 *   onSelect    — (item | null) => void
 *   inputId     — id поля (на него ссылается <label> диалога)
 *   placeholder — подсказка в пустом поле
 */
const PlaceholderSearchField = ({ spec, selected, onSelect, inputId, placeholder }) => {
  const { t } = useTranslation('chat');
  // Разбираем по полям прямо на вызове: react-hooks/refs не различает, какое
  // свойство возвращённого объекта — ref, и считает рефом любое чтение с него.
  const {
    open,
    query,
    results,
    loading,
    idx,
    wrapRef,
    inputRef,
    listRef,
    setIdx,
    openSearch,
    close,
    handleChange: onQueryChange,
    handleKeyDown: onListKeyDown,
  } = useSearchDropdown(spec.search);

  // Список показываем только когда есть что искать: пустой дропдаун на фокусе
  // перекрывал бы соседние поля формы.
  const listOpen = open && query.trim().length > 0;
  const activeId = listOpen && results.length > 0 ? `${inputId}-opt-${idx}` : undefined;

  const choose = (item) => {
    onSelect(item);
    close();
  };

  const handleChange = (e) => {
    // Правка текста снимает выбор: показанный заголовок снова становится запросом.
    if (selected) onSelect(null);
    onQueryChange(e);
  };

  const handleKeyDown = (e) => {
    // Escape при открытом списке принадлежит списку. Без этого он дошёл бы до
    // document-слушателя ModalShell и закрыл всю модалку вместе с набранным.
    if (listOpen && e.key === 'Escape') e.stopPropagation();
    onListKeyDown(e, choose);
  };

  return (
    <div className="phrase-fill__combo" ref={wrapRef}>
      <input
        id={inputId}
        ref={inputRef}
        className="phrase-fill__input"
        type="text"
        autoComplete="off"
        role="combobox"
        aria-expanded={listOpen}
        aria-controls={`${inputId}-list`}
        aria-activedescendant={activeId}
        aria-autocomplete="list"
        placeholder={placeholder}
        value={selected ? spec.describe(selected).title : query}
        onFocus={openSearch}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
      />

      {listOpen && (
        <div className="phrase-fill__results">
          {loading && <div className="phrase-fill__status">{t('phraseFill.searching')}</div>}
          {!loading && results.length === 0 && (
            <div className="phrase-fill__status">{t('phraseFill.nothingFound')}</div>
          )}

          <div className="phrase-fill__options" id={`${inputId}-list`} role="listbox" ref={listRef}>
            {results.map((item, i) => {
              const row = spec.describe(item);
              return (
                <div
                  key={row.key}
                  id={`${inputId}-opt-${i}`}
                  role="option"
                  aria-selected={i === idx}
                  className={`phrase-fill__option${i === idx ? ' phrase-fill__option--active' : ''}`}
                  onMouseDown={(e) => {
                    e.preventDefault(); // не отдаём фокус строке — он нужен полю
                    choose(item);
                  }}
                  onMouseEnter={() => setIdx(i)}
                >
                  <span className="phrase-fill__option-icon" aria-hidden="true">
                    {row.icon}
                  </span>
                  <span className="phrase-fill__option-body">
                    <span className="phrase-fill__option-title">{highlightMatch(row.title, query)}</span>
                    <span className="phrase-fill__option-sub">{row.subtitle}</span>
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

export default PlaceholderSearchField;
