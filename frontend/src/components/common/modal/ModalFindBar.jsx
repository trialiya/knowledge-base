import { useTranslation } from 'react-i18next';
import { IconSearch, IconX, IconChevronUp, IconChevronDown } from '@/icons/index';
import './modalFind.css';

/**
 * Find-бар модалки (Ctrl+F внутри открытого диалога). Поиск, подсветка и
 * прокрутка к совпадению — в useModalFind; этот компонент только рисует поле,
 * счётчик и стрелки prev/next.
 *
 * data-modal-find-bar — метка для useModalFind: собственный текст бара из поиска
 * исключается, а его перерисовки не считаются изменением содержимого модалки.
 */
const ModalFindBar = ({ inputRef, query, onQueryChange, total, activeIndex, onPrev, onNext, onClose }) => {
  const { t } = useTranslation();

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (e.shiftKey) onPrev();
      else onNext();
    }
  };

  const counter = query.trim() ? `${total ? activeIndex + 1 : 0}/${total}` : '';

  return (
    <div className="modal-find" data-modal-find-bar="">
      <span className="modal-find__icon">
        <IconSearch size={13} />
      </span>
      <input
        ref={inputRef}
        type="text"
        className="modal-find__input"
        placeholder={t('modalFind.placeholder')}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={handleKeyDown}
        autoFocus
      />
      {counter && <span className="modal-find__count">{counter}</span>}
      <button className="icon-btn" onClick={onPrev} disabled={!total} title={t('modalFind.prev')} type="button">
        <IconChevronUp size={13} />
      </button>
      <button className="icon-btn" onClick={onNext} disabled={!total} title={t('modalFind.next')} type="button">
        <IconChevronDown size={13} />
      </button>
      <button className="icon-btn" onClick={onClose} title={t('modalFind.close')} type="button">
        <IconX size={11} />
      </button>
    </div>
  );
};

export default ModalFindBar;
