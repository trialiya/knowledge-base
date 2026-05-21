import React, { useState, useEffect, useRef, useCallback } from 'react';
import { IconFolder, IconDoc, IconChevron, IconLock } from './icons';
import { findNodeById } from '../Utils/utils';

/*
 * Shared module-level drag state.
 *
 * Why not e.dataTransfer.getData() inside dragover?
 * Browsers (Chrome, Safari, Firefox) deliberately block reading drag data
 * during `dragover`/`dragenter` for security reasons — getData() returns "".
 * The data is only readable in `drop`. That means we cannot identify the
 * dragged node during hover using dataTransfer alone.
 *
 * Workaround: stash the dragged node info in a module-level ref at dragstart
 * and read it back during dragover. dataTransfer is still set as a fallback
 * for the drop handler (and for cross-window cases).
 */
const dragState = { current: null };

const DragHandle = ({ disabled }) =>
  disabled ? (
    // Spacer keeps alignment intact; no drag affordance for system nodes
    <span className="tree-row__drag-handle tree-row__drag-handle--disabled" aria-hidden="true" />
  ) : (
    <span className="tree-row__drag-handle" title="Drag to reorder">
      <svg width="10" height="14" viewBox="0 0 10 14" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="3" cy="2" r="1.2" fill="currentColor" />
        <circle cx="7" cy="2" r="1.2" fill="currentColor" />
        <circle cx="3" cy="7" r="1.2" fill="currentColor" />
        <circle cx="7" cy="7" r="1.2" fill="currentColor" />
        <circle cx="3" cy="12" r="1.2" fill="currentColor" />
        <circle cx="7" cy="12" r="1.2" fill="currentColor" />
      </svg>
    </span>
  );

const TreeNode = ({ node, level, selectedId, onSelect, onDelete, onReorder, onLoadChildren }) => {
  const [open, setOpen] = useState(false);
  const [dropPos, setDropPos] = useState(null); // 'before' | 'after' | 'inside'
  const rowRef = useRef(null);

  const isFolder = node.type === 'folder';
  const isSystem = !!node.system;
  // hasChildren from server OR already loaded children
  const hasChildren = isFolder && (node.hasChildren || (node.children && node.children.length > 0));
  const childrenLoaded = node._childrenLoaded || (node.children && node.children.length > 0);
  const isSelected = node.id === selectedId;

  // Auto-open ancestor nodes pre-loaded for direct-link navigation
  useEffect(() => {
    if (node._openOnLoad && !open) {
      setOpen(true);
    }
  }, [node._openOnLoad]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-expand ancestor when selection changes
  useEffect(() => {
    if (isFolder && node.children && findNodeById(node.children, selectedId)) {
      setOpen(true);
    }
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggleOpen = useCallback(
    async (e) => {
      if (e) e.stopPropagation();
      if (!open && isFolder && !childrenLoaded && onLoadChildren) {
        await onLoadChildren(node.id);
      }
      setOpen((o) => !o);
    },
    [open, isFolder, childrenLoaded, onLoadChildren, node.id],
  );

  // ── Drag source ───────────────────────────────────────────────────────────

  const handleDragStart = (e) => {
    // System nodes cannot be moved
    if (isSystem) {
      e.preventDefault();
      return;
    }
    e.stopPropagation();
    const payload = {
      id: node.id,
      title: node.title,
      parentId: node.parentId ?? null,
      type: node.type,
    };
    dragState.current = payload;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', JSON.stringify(payload));

    requestAnimationFrame(() => {
      rowRef.current?.classList.add('tree-row--dragging');
    });
  };

  const handleDragEnd = (e) => {
    e.stopPropagation();
    rowRef.current?.classList.remove('tree-row--dragging');
    setDropPos(null);
    dragState.current = null;
  };

  // ── Drop target ───────────────────────────────────────────────────────────

  const getDropPosition = (e) => {
    const rect = rowRef.current?.getBoundingClientRect();
    if (!rect) return 'after';
    const y = e.clientY - rect.top;
    const h = rect.height;
    if (isFolder && y > h * 0.25 && y < h * 0.75) return 'inside';
    return y < h / 2 ? 'before' : 'after';
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();

    const payload = dragState.current;
    if (!payload || payload.id === node.id) {
      setDropPos(null);
      return;
    }

    const pos = getDropPosition(e);

    // Cannot reorder a system node by dropping before/after it
    // (dropping inside a system folder is allowed)
    if (isSystem && pos !== 'inside') {
      e.dataTransfer.dropEffect = 'none';
      setDropPos(null);
      return;
    }

    e.dataTransfer.dropEffect = 'move';
    setDropPos(pos);
  };

  const handleDragLeave = (e) => {
    if (!rowRef.current?.contains(e.relatedTarget)) setDropPos(null);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setDropPos(null);

    let payload = dragState.current;
    if (!payload) {
      try {
        payload = JSON.parse(e.dataTransfer.getData('text/plain'));
      } catch {
        return;
      }
    }
    if (!payload?.id || payload.id === node.id) return;

    const pos = getDropPosition(e);

    // Block reordering around system nodes (inside is still allowed)
    if (isSystem && pos !== 'inside') return;

    onReorder({
      draggedId: payload.id,
      draggedTitle: payload.title,
      draggedParent: payload.parentId,
      targetId: node.id,
      targetParent: node.parentId ?? null,
      position: pos,
    });

    if (pos === 'inside' && isFolder) {
      if (!childrenLoaded && onLoadChildren) onLoadChildren(node.id);
      setOpen(true);
    }
  };

  // ── Render ────────────────────────────────────────────────────────────────

  const dropClass =
    dropPos === 'before'
      ? 'tree-row--drop-before'
      : dropPos === 'after'
      ? 'tree-row--drop-after'
      : dropPos === 'inside'
      ? 'tree-row--drop-inside'
      : '';

  return (
    <div className="tree-node-wrap">
      <div
        ref={rowRef}
        className={`tree-row ${isSelected ? 'tree-row--selected' : ''} ${dropClass} ${
          isSystem ? 'tree-row--system' : ''
        }`}
        style={{ '--depth': level }}
        draggable={!isSystem}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => {
          onSelect(node);
          if (isFolder) toggleOpen(null);
        }}
      >
        <DragHandle disabled={isSystem} />

        <span className="tree-row__chevron" onClick={(e) => toggleOpen(e)}>
          {hasChildren ? <IconChevron open={open} /> : <span style={{ display: 'inline-block', width: 12 }} />}
        </span>

        <span className={`tree-row__icon ${isFolder ? 'tree-row__icon--folder' : 'tree-row__icon--doc'}`}>
          {isFolder ? <IconFolder /> : <IconDoc />}
        </span>

        <span className="tree-row__label">{node.title}</span>

        {isSystem ? (
          /* Lock badge — visually signals the node is protected */
          <span className="tree-row__system-badge" title="Системный документ: удаление и переименование недоступны">
            <IconLock />
          </span>
        ) : (
          <button
            className="tree-row__del"
            title="Удалить"
            onClick={(e) => {
              e.stopPropagation();
              onDelete(node.id);
            }}
          >
            ✕
          </button>
        )}
      </div>

      {hasChildren && open && (
        <div className="tree-children">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              level={level + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              onDelete={onDelete}
              onReorder={onReorder}
              onLoadChildren={onLoadChildren}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default TreeNode;
