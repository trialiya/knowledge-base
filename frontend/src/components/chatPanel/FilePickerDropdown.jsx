import React, { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFileText } from '../../icons';

/**
 * Плавающий список результатов поиска файлов репозитория для триггера `/file`.
 * Открывается НАД кареткой (композер прижат к низу окна).
 *
 * Props:
 *   results     — GitFileNode[] { path, name, size }
 *   loading     — boolean
 *   query       — string (для пустого состояния)
 *   anchorRect  — DOMRect-like { top, left } каретки
 *   selectedIdx — number
 *   onSelect(node) — клик/Enter
 *   onDismiss()    — закрыть
 */
const FilePickerDropdown = ({ results, loading, query, anchorRect, selectedIdx, onSelect, onDismiss }) => {
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

  return (
    <div className="file-picker-dropdown" style={style}>
      <div className="file-picker-dropdown__header">
        <span className="file-picker-dropdown__hint">
          {query ? t('fileInput.hintQuery', { query }) : t('fileInput.hintStart')}
        </span>
      </div>

      {loading && (
        <div className="file-picker-dropdown__loading">
          <span className="file-picker-dropdown__spinner" />
          {t('fileInput.searching')}
        </div>
      )}

      {!loading && results.length === 0 && query.length >= 1 && (
        <div className="file-picker-dropdown__empty">{t('fileInput.empty')}</div>
      )}

      <div className="file-picker-dropdown__list" ref={listRef}>
        {results.map((node, i) => {
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
            </div>
          );
        })}
      </div>

      <div className="file-picker-dropdown__footer">
        <kbd>↑↓</kbd> {t('fileInput.navigate')} · <kbd>Enter</kbd> {t('fileInput.insert')} · <kbd>Esc</kbd>{' '}
        {t('fileInput.dismiss')}
      </div>
    </div>
  );
};

export default FilePickerDropdown;
