import { useTranslation } from 'react-i18next';
import { IconSearch, IconX, IconChevronUp, IconChevronDown } from '@/icons/index';
import './findBar.css';

/**
 * Полоса поиска по содержимому: поле, счётчик и стрелки prev/next. Сам поиск,
 * подсветка и прокрутка к совпадению — в useFindMatches; здесь только разметка.
 *
 * Один и тот же бар в двух местах: внутри открытой модалки (Ctrl+F ищет по
 * диалогу, а не по спрятанной под ним странице) и над открытым файлом (там же
 * подсвечивается запрос, с которым пришли из поиска). Отличаются они только
 * подписью поля и тем, как бар лёг в свою поверхность, — за это отвечает
 * className от вызывающего.
 *
 * data-find-bar — метка для useFindMatches: собственный текст бара из поиска
 * исключается, а его перерисовки не считаются изменением содержимого.
 *
 * props:
 *   note     — приписка справа от счётчика: чем найденное неполно (обрезанный файл)
 *   loading  — совпадения ещё ищутся: у ленты чата их считает бэкенд, и счётчик
 *              до ответа относился бы к прошлому запросу
 *   onCommit — необязательный: запрос набран окончательно (Enter, уход фокуса)
 */
const FindBar = ({
  className = '',
  inputRef,
  placeholder,
  query,
  onQueryChange,
  total,
  activeIndex,
  note,
  loading = false,
  onCommit,
  onPrev,
  onNext,
  onClose,
}) => {
  const { t } = useTranslation();

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      // Enter — и «следующее совпадение», и точка фиксации набранного: у
      // поверхности, которая хранит запрос вне бара (адрес открытого файла),
      // это её единственный шанс записать его без буквы-за-буквой.
      onCommit?.();
      if (e.shiftKey) onPrev();
      else onNext();
    }
  };

  const counter = loading ? t('find.searching') : query.trim() ? `${total ? activeIndex + 1 : 0}/${total}` : '';

  return (
    <div className={`find-bar${className ? ` ${className}` : ''}`} data-find-bar="">
      <span className="find-bar__icon">
        <IconSearch size={13} />
      </span>
      <input
        ref={inputRef}
        type="text"
        className="find-bar__input"
        placeholder={placeholder}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={handleKeyDown}
        onBlur={() => onCommit?.()}
        autoFocus
      />
      {counter && <span className="find-bar__count">{counter}</span>}
      {counter && note && <span className="find-bar__note">{note}</span>}
      <button className="icon-btn" onClick={onPrev} disabled={!total} title={t('find.prev')} type="button">
        <IconChevronUp size={13} />
      </button>
      <button className="icon-btn" onClick={onNext} disabled={!total} title={t('find.next')} type="button">
        <IconChevronDown size={13} />
      </button>
      <button className="icon-btn" onClick={onClose} title={t('find.close')} type="button">
        <IconX size={11} />
      </button>
    </div>
  );
};

export default FindBar;
