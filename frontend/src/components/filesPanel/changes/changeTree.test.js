import { buildChangeTree } from './changeTree';

const entry = (path, status = 'M') => ({ status, path, oldPath: null, additions: 1, deletions: 0, patch: null });

/** Пути листьев дерева в порядке обхода — по ним и сверяем форму. */
const leaves = (nodes) => nodes.flatMap((n) => (n.type === 'file' ? [n.path] : leaves(n.children)));

describe('buildChangeTree', () => {
  test('groups files by their directories, directories before files', () => {
    const tree = buildChangeTree([entry('README.md'), entry('src/app.js'), entry('src/ui/Btn.jsx')]);

    expect(tree.map((n) => [n.type, n.name])).toEqual([
      ['dir', 'src'],
      ['file', 'README.md'],
    ]);
    expect(leaves(tree)).toEqual(['src/ui/Btn.jsx', 'src/app.js', 'README.md']);
  });

  test('collapses a chain of single-child directories into one row', () => {
    const tree = buildChangeTree([entry('backend/src/main/java/App.java')]);

    expect(tree).toHaveLength(1);
    expect(tree[0].name).toBe('backend/src/main/java');
    // Путь узла — самый глубокий каталог цепочки: он и есть то, что строка
    // сворачивает и раскрывает.
    expect(tree[0].path).toBe('backend/src/main/java');
    expect(leaves(tree)).toEqual(['backend/src/main/java/App.java']);
  });

  test('stops collapsing where a directory branches or holds files of its own', () => {
    const tree = buildChangeTree([entry('a/b/one.txt'), entry('a/c/two.txt')]);

    expect(tree.map((n) => n.name)).toEqual(['a']);
    expect(tree[0].children.map((n) => n.name)).toEqual(['b', 'c']);
  });

  test('keeps the backend order of files inside one directory', () => {
    const tree = buildChangeTree([entry('src/z.js'), entry('src/a.js')]);

    expect(leaves(tree)).toEqual(['src/z.js', 'src/a.js']);
  });
});
