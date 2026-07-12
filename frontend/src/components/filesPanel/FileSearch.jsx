import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import { highlightFileMatch } from '../common/highlightMatch';
import { IconSearch, IconX, IconDoc } from '../../icons';

const DEBOUNCE_MS = 200;
const RESULT_LIMIT = 15;

/**
 * Быстрый поиск файла по имени над деревом: кнопка-лупа → инпут → плавающий
 * список результатов (портал, позиционируется под инпутом). Выбор — Enter,
 * клик по строке или ↑↓+Enter — вызывает onSelect(path) и закрывает поиск.
 * Паттерн подсказки заимствован у FileChipInput/FilePickerDropdown (композер
 * чата), но своя, более простая реализация: там список открывается НАД
 * кареткой (композер прижат к низу окна) и умеет вставлять контент чипом —
 * здесь нужен список ПОД полем и только переход к файлу.
 */
const FileSearch = ({ onSelect }) => {
  const { t } = useTranslation('files');
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
    gitApi
      .searchFiles(q, RESULT_LIMIT, controller.signal)
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
    (node) => {
      onSelect(node.path);
      close();
    },
    [onSelect, close],
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
      if (e.target.closest?.('.file-search-dropdown')) return;
      close();
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open, close]);

  useEffect(() => {
    listRef.current?.children[idx]?.scrollIntoView({ block: 'nearest' });
  }, [idx]);

  return (
    <div className="file-search" ref={wrapRef}>
      {open ? (
        <div className="file-search__field">
          <span className="file-search__field-icon">
            <IconSearch size={13} />
          </span>
          <input
            ref={inputRef}
            type="text"
            className="file-search__input"
            placeholder={t('search.placeholder')}
            value={query}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
          />
          <button className="file-search__close" title={t('search.close')} onClick={close}>
            <IconX size={11} />
          </button>
        </div>
      ) : (
        <button
          className="file-search__trigger"
          onClick={openSearch}
          aria-label={t('search.open')}
          title={t('search.open')}
        >
          <IconSearch size={14} />
        </button>
      )}

      {open &&
        anchorRect &&
        createPortal(
          <div
            className="file-search-dropdown"
            style={{ top: anchorRect.bottom + 6, left: anchorRect.left, width: Math.max(anchorRect.width, 360) }}
          >
            {loading && <div className="file-search-dropdown__msg">{t('search.searching')}</div>}
            {!loading && query.trim().length >= 1 && results.length === 0 && (
              <div className="file-search-dropdown__msg">{t('search.empty')}</div>
            )}
            {!loading && query.trim().length === 0 && (
              <div className="file-search-dropdown__msg">{t('search.hint')}</div>
            )}
            {results.length > 0 && (
              <div className="file-search-dropdown__list" ref={listRef}>
                {results.map((node, i) => {
                  const { name, dir } = highlightFileMatch(node.name, node.path, query);
                  return (
                    <button
                      key={node.path}
                      type="button"
                      className={`file-search-item${i === idx ? ' file-search-item--selected' : ''}`}
                      onMouseEnter={() => setIdx(i)}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        choose(node);
                      }}
                    >
                      <span className="file-search-item__icon">
                        <IconDoc size={13} />
                      </span>
                      <span className="file-search-item__body">
                        <span className="file-search-item__name">{name}</span>
                        {dir && <span className="file-search-item__path">{dir}</span>}
                      </span>
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

export default FileSearch;
