import { ancestorsOf, isActionable, selectAllActionable, summarizeSelection, toggleEntry } from './syncSelection';

// Дерево сравнения: новая папка guides с новым ребёнком, изменённый документ,
// совпадающий документ и запись, которой больше нет в папке экспорта.
const entries = [
  { path: 'guides', title: 'Guides', type: 'folder', status: 'added', depth: 0 },
  { path: 'guides/setup', title: 'Setup', type: 'document', status: 'added', depth: 1 },
  { path: 'guides/faq', title: 'FAQ', type: 'document', status: 'added', depth: 1 },
  { path: 'intro', title: 'Intro', type: 'document', status: 'modified', depth: 0 },
  { path: 'about', title: 'About', type: 'document', status: 'unchanged', depth: 0 },
  { path: 'legacy', title: 'Legacy', type: 'document', status: 'missing', depth: 0 },
];

describe('ancestorsOf', () => {
  test('перечисляет предков от корня, сам путь не включая', () => {
    expect(ancestorsOf('a/b/c')).toEqual(['a', 'a/b']);
    expect(ancestorsOf('a')).toEqual([]);
  });
});

describe('isActionable', () => {
  test('совпадающие записи выбирать нечего', () => {
    expect(isActionable({ status: 'unchanged' })).toBe(false);
    expect(entries.filter(isActionable)).toHaveLength(5);
  });
});

describe('toggleEntry', () => {
  test('включение ребёнка тянет за собой новую папку-предка', () => {
    const next = toggleEntry(entries, new Set(), 'guides/setup');
    expect([...next].sort()).toEqual(['guides', 'guides/setup']);
  });

  test('предок, который уже есть в базе, за собой не тянется', () => {
    const withExistingFolder = entries.map((e) => (e.path === 'guides' ? { ...e, status: 'unchanged' } : e));
    const next = toggleEntry(withExistingFolder, new Set(), 'guides/setup');
    expect([...next]).toEqual(['guides/setup']);
  });

  test('выключение папки снимает и её потомков', () => {
    const all = selectAllActionable(entries);
    const next = toggleEntry(entries, all, 'guides');
    expect(next.has('guides')).toBe(false);
    expect(next.has('guides/setup')).toBe(false);
    expect(next.has('guides/faq')).toBe(false);
    // Соседние ветки не трогаем.
    expect(next.has('intro')).toBe(true);
  });

  test('повторное переключение возвращает исходный выбор', () => {
    const once = toggleEntry(entries, new Set(), 'intro');
    expect([...toggleEntry(entries, once, 'intro')]).toEqual([]);
  });
});

describe('summarizeSelection', () => {
  test('считает только отмеченное и только по статусам, которые что-то делают', () => {
    const selected = new Set(['guides', 'guides/setup', 'intro', 'legacy', 'about']);
    expect(summarizeSelection(entries, selected)).toEqual({ added: 2, modified: 1, missing: 1 });
  });
});
