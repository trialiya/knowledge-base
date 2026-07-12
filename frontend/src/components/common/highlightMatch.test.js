import { matchIndices, renderHighlighted, highlightFileMatch } from './highlightMatch';

// Собрать из результата renderHighlighted строку, где подсвеченные фрагменты
// обёрнуты в [..] — так тесты читаются как «что видит пользователь».
function toMarkedString(nodes) {
  if (typeof nodes === 'string') return nodes;
  return nodes.map((n) => (typeof n === 'string' ? n : `[${n.props.children}]`)).join('');
}

describe('matchIndices', () => {
  it('находит непрерывную подстроку без учёта регистра', () => {
    expect(matchIndices('GitService.java', 'service')).toEqual([3, 4, 5, 6, 7, 8, 9]);
  });

  it('падает обратно на подпоследовательность (fuzzy), когда подстроки нет', () => {
    // g-i-t-s-v — не подстрока, но подпоследовательность GitService
    expect(matchIndices('GitService.java', 'gitsv')).toEqual([0, 1, 2, 3, 6]);
  });

  it('возвращает null, когда query не является подпоследовательностью', () => {
    expect(matchIndices('GitService.java', 'xyz')).toBeNull();
  });

  it('возвращает null для пустого query и пустого text', () => {
    expect(matchIndices('abc', '')).toBeNull();
    expect(matchIndices('abc', '   ')).toBeNull();
    expect(matchIndices('', 'a')).toBeNull();
  });

  it('обрезает пробелы вокруг query (как q.strip() на бэкенде)', () => {
    expect(matchIndices('abc', ' b ')).toEqual([1]);
  });
});

describe('renderHighlighted', () => {
  it('возвращает text как есть без индексов', () => {
    expect(renderHighlighted('abc', null)).toBe('abc');
    expect(renderHighlighted('abc', [])).toBe('abc');
  });

  it('оборачивает непрерывный участок в один mark', () => {
    expect(toMarkedString(renderHighlighted('GitService.java', matchIndices('GitService.java', 'service')))).toBe(
      'Git[Service].java',
    );
  });

  it('сливает соседние индексы и разделяет разрывы', () => {
    expect(toMarkedString(renderHighlighted('GitService.java', matchIndices('GitService.java', 'gitsv')))).toBe(
      '[GitS]er[v]ice.java',
    );
  });

  it('подсвечивает совпадение в начале и в конце строки', () => {
    expect(toMarkedString(renderHighlighted('abc', matchIndices('abc', 'a')))).toBe('[a]bc');
    expect(toMarkedString(renderHighlighted('abc', matchIndices('abc', 'c')))).toBe('ab[c]');
    expect(toMarkedString(renderHighlighted('abc', matchIndices('abc', 'abc')))).toBe('[abc]');
  });
});

describe('highlightFileMatch', () => {
  it('подсвечивает имя, когда query матчится по имени', () => {
    const { name, dir } = highlightFileMatch('GitService.java', 'backend/src/GitService.java', 'gitser');
    expect(toMarkedString(name)).toBe('[GitSer]vice.java');
    expect(dir).toBe('backend/src'); // каталог остаётся без подсветки
  });

  it('раскладывает совпадение по полному пути на dir и name', () => {
    const { name, dir } = highlightFileMatch('api.js', 'backend/api.js', 'backend/api');
    expect(toMarkedString(dir)).toBe('[backend]');
    expect(toMarkedString(name)).toBe('[api].js');
  });

  it('без совпадения возвращает исходные строки', () => {
    const { name, dir } = highlightFileMatch('api.js', 'backend/api.js', 'zzz');
    expect(name).toBe('api.js');
    expect(dir).toBe('backend');
  });

  it('файл в корне: dir пустой, имя подсвечивается', () => {
    const { name, dir } = highlightFileMatch('README.md', 'README.md', 'read');
    expect(toMarkedString(name)).toBe('[READ]ME.md');
    expect(dir).toBe('');
  });
});
