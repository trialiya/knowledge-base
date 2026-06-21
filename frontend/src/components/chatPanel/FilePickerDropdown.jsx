import React, { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFileText } from '../../icons';

/**
 * Плавающий список результатов поиска для триггеров `/file` и `/doc`.
 * Открывается НАД кареткой (композер прижат к низу окна).
 *
 * Props:
 *   results              — GitFileNode[] | DocumentNode[]
 *   loading              — boolean
 *   query                — string
 *   anchorRect           — { top, left } каретки
 *   selectedIdx          — number
 *   type                 — 'file' | 'doc'
 *   onSelect(node)       — Enter / клик по строке → вставить ссылку
 *   onSelectWithContent  — клик по кнопке → вставить содержимое
 *   onDismiss()          — закрыть
 */
const FilePickerDropdown = ({
  results,
  loading,
  query,
  anchorRect,
  selectedIdx,
  onSelect,
  onSelectWithContent,
  onDismiss,
  type = 'file',
}) => {
  const { t } = useTranslation('chat');
  const listRef = useRef(null);

  useEffect(() => {
    listRef.current?.children[selectedIdx]?.scrollIntoView({ block: 'nearest' });
  }, [selectedIdx]);

  useEffect(() => {
    const handler = (e) => {
      if (!e.target.closest?.('.file-picker-dropdown')) onDismiss();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onDismiss]);

  if (!anchorRect) return null;

  const style = {
    position: 'fixed',
    bottom: window.innerHeight - anchorRect.top + 6,
    left: Math.min(anchorRect.left, window.innerWidth - 524),
    zIndex: 9100,
  };

  const hintKey = query
    ? type === 'doc'
      ? t('docInput.hintQuery', { query })
      : t('fileInput.hintQuery', { query })
    : type === 'doc'
    ? t('docInput.hintStart')
    : t('fileInput.hintStart');

  const searchingLabel = type === 'doc' ? t('docInput.searching') : t('fileInput.searching');
  const emptyLabel = type === 'doc' ? t('docInput.empty') : t('fileInput.empty');
  const contentBtnLabel = t('fileInput.insertContent');

  return (
    <div className="file-picker-dropdown" style={style}>
      <div className="file-picker-dropdown__header">
        <span className="file-picker-dropdown__hint">{hintKey}</span>
      </div>

      {loading && (
        <div className="file-picker-dropdown__loading">
          <span className="file-picker-dropdown__spinner" />
          {searchingLabel}
        </div>
      )}

      {!loading && results.length === 0 && query.length >= 1 && (
        <div className="file-picker-dropdown__empty">{emptyLabel}</div>
      )}

      <div className="file-picker-dropdown__list" ref={listRef}>
        {type === 'doc'
          ? results.map((node, i) => (
              <div
                key={node.id}
                className={`file-picker-item ${i === selectedIdx ? 'file-picker-item--selected' : ''}`}
                onMouseDown={(e) => {
                  e.preventDefault();
                  onSelect(node);
                }}
              >
                <span className="file-picker-item__icon">{node.type === 'folder' ? '📁' : '📋'}</span>
                <span className="file-picker-item__body">
                  <span className="file-picker-item__name">{node.title}</span>
                  <span className="file-picker-item__path">#{node.id}</span>
                </span>
                <div className="file-picker-item__actions">
                  <button
                    type="button"
                    className="file-picker-item__content-btn"
                    title={contentBtnLabel}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      onSelectWithContent(node);
                    }}
                  >
                    📄 {contentBtnLabel}
                  </button>
                </div>
              </div>
            ))
          : results.map((node, i) => {
              const dir = node.path.includes('/') ? node.path.slice(0, node.path.lastIndexOf('/')) : '';
              return (
                <div
                  key={node.path}
                  className={`file-picker-item ${i === selectedIdx ? 'file-picker-item--selected' : ''}`}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    onSelect(node);
                  }}
                >
                  <span className="file-picker-item__icon">
                    <IconFileText size={13} />
                  </span>
                  <span className="file-picker-item__body">
                    <span className="file-picker-item__name">{node.name}</span>
                    <span className="file-picker-item__path" title={node.path}>
                      {dir || node.path}
                    </span>
                  </span>
                  <div className="file-picker-item__actions">
                    <button
                      type="button"
                      className="file-picker-item__content-btn"
                      title={contentBtnLabel}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        onSelectWithContent(node);
                      }}
                    >
                      📄 {contentBtnLabel}
                    </button>
                  </div>
                </div>
              );
            })}
      </div>

      <div className="file-picker-dropdown__footer">
        <kbd>↑↓</kbd> {t('fileInput.navigate')} · <kbd>Enter</kbd> {t('fileInput.insertRef')} · <kbd>Esc</kbd>{' '}
        {t('fileInput.dismiss')}
      </div>
    </div>
  );
};

export default FilePickerDropdown;
