// ─── Pure tree operations ───────────────────────────────────────────────────
// Side-effect-free helpers for manipulating the document tree. No React, no
// fetch — easy to reason about and test in isolation.

import { findNodeById } from '../Utils/utils';

/** Children of a given parent (null = root level). */
export function getSiblings(tree, parentId) {
  if (parentId === null) return tree;
  const parent = findNodeById(tree, parentId);
  return parent?.children ?? [];
}

/**
 * Returns a new tree with the dragged node moved according to dropInfo.
 * Returns the original `tree` reference unchanged when the move is invalid.
 */
export function applyReorder(tree, { draggedId, draggedParent, targetId, targetParent, position }) {
  const clone = JSON.parse(JSON.stringify(tree));

  const getChildren = (parentId) => {
    if (parentId === null) return clone;
    return findNodeById(clone, parentId)?.children ?? [];
  };

  const srcList = getChildren(draggedParent);
  const dragIdx = srcList.findIndex((n) => n.id === draggedId);
  if (dragIdx === -1) return tree;

  const [dragged] = srcList.splice(dragIdx, 1);

  if (position === 'inside') {
    const targetNode = findNodeById(clone, targetId);
    if (!targetNode || targetNode.type !== 'folder') return tree;
    targetNode.children = targetNode.children ?? [];
    dragged.parentId = targetId;
    targetNode.children.push(dragged);
  } else {
    const dstList = getChildren(targetParent);
    const targetIdx = dstList.findIndex((n) => n.id === targetId);
    if (targetIdx === -1) return tree;
    dragged.parentId = targetParent;
    const insertAt = position === 'before' ? targetIdx : targetIdx + 1;
    dstList.splice(insertAt, 0, dragged);
  }

  return clone;
}

/** Display name for a parent node (null = root). */
export function parentTitle(tree, parentId) {
  if (parentId === null) return 'Корневой уровень';
  return findNodeById(tree, parentId)?.title ?? 'Неизвестно';
}

/** True when a drop changes the node's parent. */
export function isParentChange({ draggedParent, targetParent, position, targetId }) {
  if (position === 'inside') return draggedParent !== targetId;
  return draggedParent !== targetParent;
}

/** Immutably patch a single node (matched by id) anywhere in the tree. */
export function updateNodeInTree(tree, id, updates) {
  return tree.map((node) => {
    if (node.id === id) return { ...node, ...updates };
    if (node.children) return { ...node, children: updateNodeInTree(node.children, id, updates) };
    return node;
  });
}

/**
 * Splices a freshly-fetched page of children into the matching parent of a
 * cloned tree. Mutates `clone` in place (caller owns the clone) and returns it.
 *
 * @param replace  when true (page 0) the children array is replaced; otherwise
 *                 new items are appended, deduplicated by id.
 */
export function spliceChildren(clone, parentId, paged, { replace = true } = {}) {
  const parent = findNodeById(clone, parentId);
  if (!parent) return clone;

  const items = Array.isArray(paged.items) ? paged.items : [];
  if (replace) {
    parent.children = items;
  } else {
    const existing = parent.children ?? [];
    const existingIds = new Set(existing.map((c) => c.id));
    parent.children = [...existing, ...items.filter((c) => !existingIds.has(c.id))];
  }

  parent._childrenLoaded = true;
  parent.hasChildren = (paged.totalElements ?? 0) > 0;
  parent._totalChildren = paged.totalElements ?? null;
  return parent;
}
