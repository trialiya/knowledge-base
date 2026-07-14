import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconChevron, IconLock, IconDragHandle } from '../../icons';
import { findNodeById } from '../common/utils';
import { KB_PAGE_SIZE as PAGE_SIZE } from '../../constants/pagination';

/*
 * Shared module-level drag state.
 * Browsers block reading drag data during dragover/dragenter for security — getData() returns "".
 * Workaround: stash the dragged node info in a module-level ref at dragstart.
 */
const dragState = { current: null };

const DragHandle = ({ disabled }) => {
  const { t } = useTranslation('knowledgeBase');
  return disabled ? (
    <span className="tree-row__drag-handle tree-row__drag-handle--disabled" aria-hidden="true" />
  ) : (
    <span className="tree-row__drag-handle" title={t('tree.dragToReorder')}>
      <IconDragHandle />
    </span>
  );
};

const TreeNode = ({ node, level, selectedId, onSelect, onDelete, onReorder, onLoadChildren }) => {
  const { t } = useTranslation('knowledgeBase');
  const [open, setOpen] = useState(false);
  const [dropPos, setDropPos] = useState(null); // 'before' | 'after' | 'inside'
  const [nextPage, setNextPage] = useState(1); // next page number to load
  const [totalElements, setTotalElements] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const rowRef = useRef(null);

  const isFolder = node.type === 'folder';
  const isSystem = !!node.system;
  const hasChildren = isFolder && (node.hasChildren || (node.children && node.children.length > 0));
  const childrenLoaded = node._childrenLoaded || (node.children && node.children.length > 0);
  const isSelected = node.id === selectedId;

  // Sync totalElements from node._totalChildren (set by KnowledgeBase)
  useEffect(() => {
    if (node._totalChildren != null) {
      setTotalElements(node._totalChildren);
    }
  }, [node._totalChildren]);

  // Reset nextPage when children are replaced (page 0 reload)
  useEffect(() => {
    if (node.children) {
      // If the number of loaded children equals one page, next page is 1.
      // Otherwise compute from the loaded count.
      const loaded = node.children.length;
      setNextPage(Math.ceil(loaded / PAGE_SIZE));
    }
  }, [node.children?.length]); // eslint-disable-line react-hooks/exhaustive-deps

  // Auto-open ancestor nodes pre-loaded for direct-link navigation
  useEffect(() => {
    if (node._openOnLoad && !open) {
      if (isFolder && !childrenLoaded && onLoadChildren) {
        onLoadChildren(node.id, 0, PAGE_SIZE).then((paged) => {
          if (paged?.totalElements != null) setTotalElements(paged.totalElements);
          setOpen(true);
        });
      } else {
        setOpen(true);
      }
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
        const paged = await onLoadChildren(node.id, 0, PAGE_SIZE);
        if (paged?.totalElements != null) setTotalElements(paged.totalElements);
      }
      setOpen((o) => !o);
    },
    [open, isFolder, childrenLoaded, onLoadChildren, node.id],
  );

  // Used by the row click (which also selects the node). It does NOT fetch:
  // selecting a folder mounts FolderDetail, whose useFolderChildren loads the
  // full child list through the shared (deduplicated) loader and splices it
  // into this same tree node. Firing a second PAGE_SIZE fetch here would just
  // duplicate that request (the size=10 + size=1000 pair). We only flip the
  // open state; children render as soon as the shared load lands in node.children.
  const toggleOpenVisual = useCallback(() => {
    setOpen((o) => !o);
  }, []);

  const handleLoadMore = useCallback(
    async (e) => {
      e.stopPropagation();
      if (loadingMore || !onLoadChildren) return;
      setLoadingMore(true);
      try {
        const paged = await onLoadChildren(node.id, nextPage, PAGE_SIZE);
        if (paged?.totalElements != null) setTotalElements(paged.totalElements);
        if (paged?.hasNext === false) {
          // All loaded — no more pages
        }
      } finally {
        setLoadingMore(false);
      }
    },
    [loadingMore, onLoadChildren, node.id, nextPage],
  );

  // ── Drag source ───────────────────────────────────────────────────────────

  const handleDragStart = (e) => {
    if (isSystem) {
      e.preventDefault();
      return;
    }
    e.stopPropagation();
    const payload = { id: node.id, title: node.title, parentId: node.parentId ?? null, type: node.type };
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
      if (!childrenLoaded && onLoadChildren) onLoadChildren(node.id, 0, PAGE_SIZE);
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

  // Show "load more" when we know total and have loaded fewer
  const knownTotal = totalElements ?? node._totalChildren ?? null;
  const currentCount = node.children?.length ?? 0;
  const showLoadMore = open && isFolder && knownTotal !== null && currentCount < knownTotal;
  const remaining = knownTotal !== null ? knownTotal - currentCount : 0;

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
          if (isFolder) toggleOpenVisual();
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
          <span className="tree-row__system-badge" title={t('detail.systemBadge')}>
            <IconLock />
          </span>
        ) : (
          <button
            className="tree-row__del"
            title={t('tree.delete')}
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

          {/* "Load more" trigger */}
          {showLoadMore && (
            <button
              className="tree-load-more"
              style={{ '--depth': level + 1 }}
              onClick={handleLoadMore}
              disabled={loadingMore}
            >
              {loadingMore ? '…' : t('tree.loadMore', { count: remaining })}
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default TreeNode;
