import React, { useEffect, useRef } from 'react';
import { IconFolder, IconDoc } from './icons';

/**
 * Floating dropdown that renders document search results for @mention.
 *
 * Props:
 *   results     — DocumentNode[]
 *   loading     — boolean
 *   query       — string (for empty-state message)
 *   anchorRect  — DOMRect-like { top, left, bottom }
 *   selectedIdx — number
 *   onSelect(node) — called when user clicks or presses Enter
 *   onDismiss()    — called to close
 */
const AtMentionDropdown = ({ results, loading, query, anchorRect, selectedIdx, onSelect, onDismiss }) => {
  const listRef = useRef(null);

  // Scroll selected item into view
  useEffect(() => {
    const el = listRef.current?.children[selectedIdx];
    el?.scrollIntoView({ block: 'nearest' });
  }, [selectedIdx]);

  // Close on outside click
  useEffect(() => {
    const handler = (e) => {
      if (!listRef.current?.closest('.at-mention-dropdown')?.contains(e.target)) {
        onDismiss();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onDismiss]);

  if (!anchorRect) return null;

  const style = {
    position: 'fixed',
    top: anchorRect.bottom + 4,
    left: Math.min(anchorRect.left, window.innerWidth - 300),
    zIndex: 9999,
  };

  return (
    <div className="at-mention-dropdown" style={style}>
      <div className="at-mention-dropdown__header">
        <span className="at-mention-dropdown__hint">{query ? `Поиск: «${query}»` : 'Начните вводить название…'}</span>
      </div>

      {loading && (
        <div className="at-mention-dropdown__loading">
          <span className="at-mention-dropdown__spinner" />
          Поиск…
        </div>
      )}

      {!loading && results.length === 0 && query.length >= 1 && (
        <div className="at-mention-dropdown__empty">Ничего не найдено</div>
      )}

      <div className="at-mention-dropdown__list" ref={listRef}>
        {results.map((node, i) => {
          const isFolder = node.type === 'folder';
          const isSelected = i === selectedIdx;
          const snippet = node.description
            ? node.description.replace(/[#*_`>\[\]]/g, '').slice(0, 70) + (node.description.length > 70 ? '…' : '')
            : null;

          return (
            <div
              key={node.id}
              className={`at-mention-item ${isSelected ? 'at-mention-item--selected' : ''}`}
              onMouseDown={(e) => {
                e.preventDefault(); // don't blur textarea
                onSelect(node);
              }}
            >
              <span className={`at-mention-item__icon ${isFolder ? 'at-mention-item__icon--folder' : ''}`}>
                {isFolder ? <IconFolder size={12} /> : <IconDoc size={12} />}
              </span>
              <span className="at-mention-item__body">
                <span className="at-mention-item__title">{node.title}</span>
                {snippet && <span className="at-mention-item__snippet">{snippet}</span>}
              </span>
              <span className="at-mention-item__type">{isFolder ? 'Folder' : 'Doc'}</span>
            </div>
          );
        })}
      </div>

      <div className="at-mention-dropdown__footer">
        <kbd>↑↓</kbd> навигация · <kbd>Enter</kbd> вставить · <kbd>Esc</kbd> закрыть
      </div>
    </div>
  );
};

export default AtMentionDropdown;
