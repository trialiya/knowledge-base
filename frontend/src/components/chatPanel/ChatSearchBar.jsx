import React from 'react';
import { useTranslation } from 'react-i18next';
import { IconSearch, IconX, IconChevronUp, IconChevronDown } from '../../icons';

/**
 * Find-бар для поиска сообщений внутри открытого чата (Ctrl+F / кнопка-лупа
 * в шапке). Сам поиск и навигация — в useInChatSearch; этот компонент только
 * рендерит поле, счётчик и стрелки prev/next.
 */
const ChatSearchBar = ({ inputRef, query, onQueryChange, total, activeIndex, loading, onPrev, onNext, onClose }) => {
  const { t } = useTranslation('chat');

  const handleKeyDown = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      if (e.shiftKey) onPrev();
      else onNext();
    }
  };

  const trimmed = query.trim();
  const counter = loading ? t('inChatSearch.searching') : trimmed ? `${total ? activeIndex + 1 : 0}/${total}` : '';

  return (
    <div className="chat-search-bar">
      <span className="chat-search-bar__icon">
        <IconSearch size={13} />
      </span>
      <input
        ref={inputRef}
        type="text"
        className="chat-search-bar__input"
        placeholder={t('inChatSearch.placeholder')}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={handleKeyDown}
        autoFocus
      />
      {counter && <span className="chat-search-bar__count">{counter}</span>}
      <button
        className="chat-search-bar__nav"
        onClick={onPrev}
        disabled={!total}
        title={t('inChatSearch.prev')}
        type="button"
      >
        <IconChevronUp size={13} />
      </button>
      <button
        className="chat-search-bar__nav"
        onClick={onNext}
        disabled={!total}
        title={t('inChatSearch.next')}
        type="button"
      >
        <IconChevronDown size={13} />
      </button>
      <button className="chat-search-bar__close" onClick={onClose} title={t('common:close')} type="button">
        <IconX size={11} />
      </button>
    </div>
  );
};

export default ChatSearchBar;
