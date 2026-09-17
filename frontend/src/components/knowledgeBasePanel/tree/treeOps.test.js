import { describe, it, expect } from 'vitest';
import { applyReorder, markOpenOnLoad, spliceChildren, mergeStubIntoSelection } from './treeOps';

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

describe('markOpenOnLoad', () => {
  it('меняет значение пометки на каждую пометку', () => {
    const tree = [folder(1, [], 0)];

    const once = markOpenOnLoad(tree, 1);
    const twice = markOpenOnLoad(once, 1);

    expect(once[0]._openOnLoad).toBeTruthy();
    // Вторая пометка обязана отличаться от первой: TreeNode ловит её по
    // изменению, и свёрнутая руками папка иначе больше не раскрылась бы.
    expect(twice[0]._openOnLoad).not.toBe(once[0]._openOnLoad);
    expect(tree[0]._openOnLoad).toBeUndefined();
  });
});

describe('spliceChildren', () => {
  it('удачная страница снимает пометку отказа', () => {
    const clone = [{ ...folder(1, [], 0), _childrenError: true }];

    spliceChildren(clone, 1, { items: [doc(10, 1)], totalElements: 1 });

    expect(clone[0]._childrenError).toBe(false);
    expect(clone[0].children).toHaveLength(1);
  });
});

describe('mergeStubIntoSelection', () => {
  const full = {
    id: 7,
    type: 'folder',
    title: 'Папка',
    description: 'полное описание',
    children: [doc(10, 7)],
    _full: true,
  };

  it('непрочитанная заглушка не стирает загруженный состав', () => {
    // Сервер шлёт children: [] и у непрочитанного узла — по нему состав папки
    // исчезал бы до следующего запроса.
    const stub = { id: 7, type: 'folder', title: 'Папка', description: 'снипп', children: [], hasChildren: true };

    const merged = mergeStubIntoSelection(full, stub);

    expect(merged.children).toHaveLength(1);
    expect(merged.description).toBe('полное описание');
  });

  it('прочитанное дерево задаёт состав, даже когда он пуст', () => {
    const stub = { id: 7, type: 'folder', title: 'Папка', children: [], _childrenLoaded: true, _totalChildren: 0 };

    expect(mergeStubIntoSelection(full, stub).children).toEqual([]);
  });

  it('заголовок берётся из дерева, полное описание остаётся своим', () => {
    const stub = {
      id: 7,
      type: 'folder',
      title: 'Новое имя',
      description: 'снипп',
      children: [],
      _childrenLoaded: true,
    };

    const merged = mergeStubIntoSelection(full, stub);

    expect(merged.title).toBe('Новое имя');
    expect(merged.description).toBe('полное описание');
  });
});
