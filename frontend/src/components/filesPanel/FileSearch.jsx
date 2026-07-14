import React, { useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import useSearchDropdown from '../../hooks/useSearchDropdown';
import { highlightFileMatch } from '../common/highlightMatch';
import { IconSearch, IconX, IconDoc } from '../../icons';

const RESULT_LIMIT = 15;

/**
 * Быстрый поиск файла по имени над деревом: кнопка-лупа → инпут → плавающий
 * список результатов (портал, позиционируется под инпутом). Выбор — Enter,
 * клик по строке или ↑↓+Enter — вызывает onSelect(path) и закрывает поиск.
 * Состояние — в общем useSearchDropdown (тот же, что у ChatSearch над списком
 * чатов); здесь — только фетч-функция и разметка строк с подсветкой имени/пути.
 */
const FileSearch = ({ onSelect }) => {
  const { t } = useTranslation('files');
  const search = useCallback((q, signal) => gitApi.searchFiles(q, RESULT_LIMIT, signal), []);
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
  } = useSearchDropdown(search);

  const choose = useCallback(
    (node) => {
      onSelect(node.path);
      close();
    },
    [onSelect, close],
  );

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
            onKeyDown={(e) => handleKeyDown(e, choose)}
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
            ref={portalRef}
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
