import { useState } from 'react';

/*
 * Shared module-level drag state.
 * Browsers block reading drag data during dragover/dragenter for security — getData() returns "".
 * Workaround: stash the dragged node info in a module-level ref at dragstart.
 */
const dragState = { current: null };

/**
 * Перетаскивание одной строки дерева: она же источник, она же цель.
 *
 * Возвращает подсказку о месте броска (`dropPos`) и готовые обработчики строки.
 * Бросок только сообщает о себе через `onReorder`: ни раскрытия папки, ни
 * загрузки её детей здесь нет — перенос ещё может не состояться (смена родителя
 * спрашивает подтверждение), а страница, прочитанная параллельно с ним, затёрла
 * бы из папки перенесённый узел. Раскрывает приёмник `executeReorder`, когда
 * перенос подтвердил сервер.
 *
 * @param {{node: object, isFolder: boolean, isSystem: boolean, rowRef: {current: ?HTMLElement}, onReorder: Function}} params
 * @returns {{dropPos: ?string, dragHandlers: object}}
 */
export default function useNodeDrag({ node, isFolder, isSystem, rowRef, onReorder }) {
  const [dropPos, setDropPos] = useState(null); // 'before' | 'after' | 'inside'

  const getDropPosition = (e) => {
    const rect = rowRef.current?.getBoundingClientRect();
    if (!rect) return 'after';
    const y = e.clientY - rect.top;
    const h = rect.height;
    if (isFolder && y > h * 0.25 && y < h * 0.75) return 'inside';
    return y < h / 2 ? 'before' : 'after';
  };

  const dragHandlers = {
    onDragStart: (e) => {
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
    },

    onDragEnd: (e) => {
      e.stopPropagation();
      rowRef.current?.classList.remove('tree-row--dragging');
      setDropPos(null);
      dragState.current = null;
    },

    onDragOver: (e) => {
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
    },

    onDragLeave: (e) => {
      if (!rowRef.current?.contains(e.relatedTarget)) setDropPos(null);
    },

    onDrop: (e) => {
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
    },
  };

  return { dropPos, dragHandlers };
}
