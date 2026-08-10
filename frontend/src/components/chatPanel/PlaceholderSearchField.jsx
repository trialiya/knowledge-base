import { useState } from 'react';
import { createPortal } from 'react-dom';
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
 *   autoFocus   — поставить фокус при открытии диалога
 */
const PlaceholderSearchField = ({ spec, selected, onSelect, inputId, placeholder, autoFocus }) => {
  const { t } = useTranslation('chat');
  // Разбираем по полям прямо на вызове: react-hooks/refs не различает, какое
  // свойство возвращённого объекта — ref, и считает рефом любое чтение с него.
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
    handleChange: onQueryChange,
    handleKeyDown: onListKeyDown,
  } = useSearchDropdown(spec.search);

  // Набранное держим у себя: `query` хука — это то, что сейчас ищется, и close()
  // его стирает. Поле живёт в форме постоянно (а не сворачивается, как PanelSearch),
  // поэтому закрытие списка — по Escape, по прокрутке колонки полей, по ресайзу —
  // иначе стирало бы текст прямо под руками.
  const [text, setText] = useState('');

  // Список показываем только когда есть что искать: пустой дропдаун на фокусе
  // перекрывал бы соседние поля формы.
  const listOpen = open && query.trim().length > 0;
  const activeId = listOpen && results.length > 0 ? `${inputId}-opt-${idx}` : undefined;

  // handleChange хука читает только e.target.value — этого хватает, чтобы завести
  // поиск по сохранённому тексту, не повторяя здесь его дебаунс.
  const searchFor = (value) => onQueryChange({ target: { value } });

  const choose = (item) => {
    onSelect(item);
    setText('');
    close();
  };

  const handleChange = (e) => {
    // Правка текста снимает выбор: показанный заголовок снова становится запросом.
    if (selected) onSelect(null);
    // Выбор элемента закрыл список (close), и без повторного открытия правка уже
    // выбранного значения искала бы вхолостую — в невидимый список.
    if (!open) openSearch();
    setText(e.target.value);
    onQueryChange(e);
  };

  // Список мог закрыться сам (прокрутка колонки полей, ресайз, Escape), а текст в
  // поле остался — возвращаемся к поиску по нему. Слушаем и клик: закрытие фокус
  // из поля не уводит, так что одного onFocus не хватает — прокрутивший колонку
  // пользователь кликает в уже сфокусированное поле, и focus не наступает.
  const resumeSearch = () => {
    openSearch();
    if (!selected && text.trim() && !query) searchFor(text);
  };

  const handleKeyDown = (e) => {
    // Пока список открыт, Escape принадлежит ему: без этого он дошёл бы до
    // document-слушателя ModalShell и закрыл всю модалку вместе с набранным.
    // Enter — только пока в списке есть что выбрать (или вот-вот появится, идёт
    // дебаунс): иначе диалог отправлялся бы, не дождавшись выдачи. На пустой
    // выдаче выбирать нечего, и Enter отправляет форму, как и должен.
    const mine = e.key === 'Escape' || (e.key === 'Enter' && (loading || results.length > 0));
    if (listOpen && mine) {
      e.preventDefault();
      e.stopPropagation();
    }
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
        autoFocus={autoFocus}
        value={selected ? spec.describe(selected).title : text}
        onFocus={resumeSearch}
        onClick={resumeSearch}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
      />

      {/* Список — в портале на body: и .modal-shell, и колонка полей внутри неё
          обрезают по overflow, так что вложенный absolute был бы не виден. */}
      {listOpen &&
        anchorRect &&
        createPortal(
          <div
            ref={portalRef}
            className="phrase-fill__results"
            style={{ top: anchorRect.bottom + 3, left: anchorRect.left, width: anchorRect.width }}
          >
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
          </div>,
          document.body,
        )}
    </div>
  );
};

export default PlaceholderSearchField;
