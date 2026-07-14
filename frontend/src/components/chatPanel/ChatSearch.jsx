import React, { useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import useSearchDropdown from '../../hooks/useSearchDropdown';
import { highlightSubstring } from '../common/highlightMatch';
import { IconSearch, IconX, IconMessage } from '../../icons';

const DEBOUNCE_MS = 250;
const RESULT_LIMIT = 15;

/**
 * Поиск по чатам (лупа над списком): и по названию, и по содержимому сообщений
 * одновременно, результаты объединены по чату. Состояние (debounce, abort,
 * клавиатурная навигация, закрытие по клику снаружи/скроллу) — в общем
 * useSearchDropdown (тот же, что у FileSearch над деревом файлов); здесь —
 * только фетч-функция и разметка строк.
 */
const ChatSearch = ({ onSelect }) => {
  const { t } = useTranslation('chat');
  const search = useCallback((q, signal) => chatApi.searchChats(q, RESULT_LIMIT, signal), []);
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
  } = useSearchDropdown(search, { debounceMs: DEBOUNCE_MS });

  const choose = useCallback(
    (result) => {
      onSelect(result, query.trim());
      close();
    },
    [onSelect, query, close],
  );

  return (
    <div className="chat-search" ref={wrapRef}>
      {open ? (
        <div className="chat-search__field">
          <span className="chat-search__field-icon">
            <IconSearch size={13} />
          </span>
          <input
            ref={inputRef}
            type="text"
            className="chat-search__input"
            placeholder={t('sidebarSearch.placeholder')}
            value={query}
            onChange={handleChange}
            onKeyDown={(e) => handleKeyDown(e, choose)}
          />
          <button className="chat-search__close" title={t('sidebarSearch.close')} onClick={close}>
            <IconX size={11} />
          </button>
        </div>
      ) : (
        <button className="chat-search__trigger" onClick={openSearch} title={t('sidebarSearch.open')}>
          <IconSearch size={14} />
          <span>{t('sidebarSearch.open')}</span>
        </button>
      )}

      {open &&
        anchorRect &&
        createPortal(
          <div
            ref={portalRef}
            className="chat-search-dropdown"
            style={{ top: anchorRect.bottom + 6, left: anchorRect.left, width: Math.max(anchorRect.width, 320) }}
          >
            {loading && <div className="chat-search-dropdown__msg">{t('sidebarSearch.searching')}</div>}
            {!loading && query.trim().length >= 1 && results.length === 0 && (
              <div className="chat-search-dropdown__msg">{t('sidebarSearch.empty')}</div>
            )}
            {!loading && query.trim().length === 0 && (
              <div className="chat-search-dropdown__msg">{t('sidebarSearch.hint')}</div>
            )}
            {results.length > 0 && (
              <div className="chat-search-dropdown__list" ref={listRef}>
                {results.map((res, i) => (
                  <button
                    key={res.conversationId}
                    type="button"
                    className={`chat-search-item${i === idx ? ' chat-search-item--selected' : ''}`}
                    onMouseEnter={() => setIdx(i)}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      choose(res);
                    }}
                  >
                    <span className="chat-search-item__icon">
                      <IconMessage size={13} />
                    </span>
                    <span className="chat-search-item__body">
                      <span className="chat-search-item__title">
                        {highlightSubstring(res.topic || t('window.defaultTitle'), query)}
                      </span>
                      {res.snippet && (
                        <span className="chat-search-item__snippet">{highlightSubstring(res.snippet, query)}</span>
                      )}
                    </span>
                    {res.messageMatchCount > 0 && (
                      <span className="chat-search-item__badge">{res.messageMatchCount}</span>
                    )}
                  </button>
                ))}
              </div>
            )}
          </div>,
          document.body,
        )}
    </div>
  );
};

export default ChatSearch;
