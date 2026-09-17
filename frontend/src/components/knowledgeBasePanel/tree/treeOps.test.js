import { describe, it, expect } from 'vitest';
import { applyReorder } from './treeOps';

/** Папка с двумя прочитанными детьми из пяти — «ещё 3» в дереве. */
const folder = (id, children, total) => ({
  id,
  type: 'folder',
  parentId: null,
  children,
  _childrenLoaded: true,
  _totalChildren: total,
});
const doc = (id, parentId) => ({ id, type: 'document', parentId, children: [] });

describe('applyReorder', () => {
  it('переезд между папками правит счётчики обеих', () => {
    const tree = [folder(1, [doc(10, 1), doc(11, 1)], 5), folder(2, [doc(20, 2)], 4)];

    const next = applyReorder(tree, {
      draggedId: 10,
      draggedParent: 1,
      targetId: 2,
      targetParent: null,
      position: 'inside',
    });

    expect(next[0]._totalChildren).toBe(4);
    expect(next[1]._totalChildren).toBe(5);
    expect(next[1].children.map((n) => n.id)).toEqual([20, 10]);
    expect(next[1].children[1].parentId).toBe(2);
  });

  it('перестановка внутри одной папки счётчик не трогает', () => {
    const tree = [folder(1, [doc(10, 1), doc(11, 1)], 5)];

    const next = applyReorder(tree, {
      draggedId: 11,
      draggedParent: 1,
      targetId: 10,
      targetParent: 1,
      position: 'before',
    });

    expect(next[0]._totalChildren).toBe(5);
    expect(next[0].children.map((n) => n.id)).toEqual([11, 10]);
  });

  // Счётчика нет, пока папку не читали, — считать тогда нечего, и выдумывать
  // число нельзя: из него выводится «ещё N».
  it('непрочитанная папка-приёмник остаётся без счётчика', () => {
    const tree = [
      folder(1, [doc(10, 1)], 3),
      { id: 2, type: 'folder', parentId: null, children: [], hasChildren: true },
    ];

    const next = applyReorder(tree, {
      draggedId: 10,
      draggedParent: 1,
      targetId: 2,
      targetParent: null,
      position: 'inside',
    });

    expect(next[1]._totalChildren).toBeUndefined();
    expect(next[0]._totalChildren).toBe(2);
  });

  it('невозможный переезд возвращает то же дерево', () => {
    const tree = [folder(1, [doc(10, 1)], 3)];

    expect(applyReorder(tree, { draggedId: 999, draggedParent: 1, targetId: 1, position: 'inside' })).toBe(tree);
  });
});
