// ─── Pure tree operations ───────────────────────────────────────────────────
// Side-effect-free helpers for manipulating the document tree. No React, no
// fetch — easy to reason about and test in isolation.

import { findNodeById } from '../common/utils';

/** Structural deep clone of a tree (single source of the clone idiom). */
export function cloneTree(tree) {
  return JSON.parse(JSON.stringify(tree));
}

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
  const clone = cloneTree(tree);

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

/**
 * Display name for a parent node (null = root).
 *
 * Pass the i18next `t` (scoped to the `knowledgeBase` namespace) to localize the
 * root/unknown labels. `t` is optional: when omitted the function falls back to
 * the original Russian wording, so unit tests can call it without an i18n setup.
 */
export function parentTitle(tree, parentId, t) {
  if (parentId === null) return t ? t('add.root') : 'Корневой уровень';
  return findNodeById(tree, parentId)?.title ?? (t ? t('tree.unknown') : 'Неизвестно');
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
 * cloned tree. Mutates `clone` in place (caller owns the clone) and returns the
 * matched parent node (or null when the parent isn't in the tree).
 *
 * @param replace  when true (page 0) the children array is replaced; otherwise
 *                 new items are appended, deduplicated by id.
 */
export function spliceChildren(clone, parentId, paged, { replace = true } = {}) {
  const parent = findNodeById(clone, parentId);
  if (!parent) return null;

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

/**
 * Pure, immutable "splice a page of children into the tree" used by every
 * loader / navigation path. Replaces the three competing idioms that used to
 * live in useKnowledgeBase (setTree+clone+splice, the threaded-currentTree
 * closure, and the resolve-inside-updater hack):
 *
 *   const next = applyChildren(tree, parentId, paged, { replace, open });
 *   setTree(next);                       // or thread `next` through async code
 *
 * @param replace  replace (page 0) vs append (subsequent pages)
 * @param open     mark the parent `_openOnLoad` so TreeNode auto-expands it
 *                 (used when restoring the path to a deep/selected node)
 * @returns a NEW tree array (the input is never mutated)
 */
export function applyChildren(tree, parentId, paged, { replace = true, open = false } = {}) {
  const clone = cloneTree(tree);
  spliceChildren(clone, parentId, paged, { replace });
  if (open && parentId !== null) {
    const parent = findNodeById(clone, parentId);
    if (parent) parent._openOnLoad = true;
  }
  return clone;
}
