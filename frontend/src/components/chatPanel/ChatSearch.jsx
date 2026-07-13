import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { highlightSubstring } from '../common/highlightMatch';
import { IconSearch, IconX, IconMessage } from '../../icons';

const DEBOUNCE_MS = 250;
const RESULT_LIMIT = 15;

/**
 * Поиск по чатам (лупа над списком): и по названию, и по содержимому сообщений
 * одновременно, результаты объединены по чату. Паттерн — как FileSearch над
 * деревом файлов (кнопка-лупа → инпут → плавающий список), своя более простая
 * реализация: список результатов, а не одиночная подсказка по имени.
 */
const ChatSearch = ({ onSelect }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [idx, setIdx] = useState(0);
  const [anchorRect, setAnchorRect] = useState(null);

  const wrapRef = useRef(null);
  const inputRef = useRef(null);
  const listRef = useRef(null);
  const debounceRef = useRef(null);
  const abortRef = useRef(null);

  const close = useCallback(() => {
    clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    setOpen(false);
    setQuery('');
    setResults([]);
    setIdx(0);
    setLoading(false);
  }, []);

  const openSearch = useCallback(() => {
    setOpen(true);
    requestAnimationFrame(() => inputRef.current?.focus());
  }, []);

  // Якорь дропдауна — позиция инпута на момент открытия.
  useEffect(() => {
    if (open) setAnchorRect(inputRef.current?.getBoundingClientRect() ?? null);
  }, [open]);

  const runSearch = useCallback((q) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    chatApi
      .searchChats(q, RESULT_LIMIT, controller.signal)
      .then((data) => {
        setResults(Array.isArray(data) ? data : []);
        setIdx(0);
        setLoading(false);
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          setResults([]);
          setLoading(false);
        }
      });
  }, []);

  const handleChange = useCallback(
    (e) => {
      const q = e.target.value;
      setQuery(q);
      clearTimeout(debounceRef.current);
      if (q.trim().length >= 1) {
        debounceRef.current = setTimeout(() => runSearch(q.trim()), DEBOUNCE_MS);
      } else {
        abortRef.current?.abort();
        setResults([]);
        setLoading(false);
      }
    },
    [runSearch],
  );

  const choose = useCallback(
    (result) => {
      onSelect(result, query.trim());
      close();
    },
    [onSelect, query, close],
  );

  const handleKeyDown = useCallback(
    (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setIdx((i) => Math.min(i + 1, results.length - 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setIdx((i) => Math.max(i - 1, 0));
        return;
      }
      if (e.key === 'Enter' && results.length > 0) {
        e.preventDefault();
        choose(results[idx]);
      }
    },
    [close, results, idx, choose],
  );

  // Клик снаружи (кнопки/инпута И плавающего списка) закрывает поиск.
  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (e) => {
      if (wrapRef.current?.contains(e.target)) return;
      if (e.target.closest?.('.chat-search-dropdown')) return;
      close();
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open, close]);

  useEffect(() => {
    if (!open) return;
    // Скролл страницы «отрывает» плавающий список от якоря — закрываем. Но прокрутка
    // внутри самого списка результатов (колёсиком или scrollIntoView при навигации
    // стрелками) закрывать поиск не должна.
    const onScroll = (e) => {
      if (e.target instanceof Element && e.target.closest('.chat-search-dropdown')) return;
      close();
    };
    window.addEventListener('resize', close);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open, close]);

  useEffect(() => {
    listRef.current?.children[idx]?.scrollIntoView({ block: 'nearest' });
  }, [idx]);

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
            onKeyDown={handleKeyDown}
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
