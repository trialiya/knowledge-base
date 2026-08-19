import { detectArgumentList } from './argumentList';

// Данные — форма настоящих `argumentsRaw`: то, что модель прислала в вызове.

describe('detectArgumentList — короткое строкой, длинное блоком', () => {
  it('getFileContent: три коротких аргумента — три строки, блоков нет', () => {
    const args = detectArgumentList(JSON.stringify({ path: 'backend/build.gradle', fromLine: 59, toLine: 120 }));
    expect(args.fields).toEqual([
      { key: 'path', value: 'backend/build.gradle' },
      { key: 'fromLine', value: '59' },
      { key: 'toLine', value: '120' },
    ]);
    expect(args.blocks).toHaveLength(0);
  });

  it('editFile: фрагмент файла уходит в блок с настоящими переносами', () => {
    const newText = "const [draft, setDraft] = useState('');\n// вторая строка\n// третья";
    const args = detectArgumentList(JSON.stringify({ path: 'a.jsx', oldText: 'x', newText }));
    expect(args.fields.map((f) => f.key)).toEqual(['path', 'oldText']);
    expect(args.blocks).toHaveLength(1);
    expect(args.blocks[0]).toMatchObject({ key: 'newText', chars: newText.length });
    expect(args.blocks[0].lines).toHaveLength(3);
  });

  it('длинная однострочная строка — тоже блок: в строку она не поместится', () => {
    const args = detectArgumentList(JSON.stringify({ query: 'а'.repeat(210) }));
    expect(args.fields).toHaveLength(0);
    expect(args.blocks[0].lines).toHaveLength(1);
  });

  it('null, число и короткий список остаются строками', () => {
    const args = detectArgumentList(JSON.stringify({ parentId: null, limit: 3, names: ['a.md', 'b.md'] }));
    expect(args.fields).toEqual([
      { key: 'parentId', value: 'null' },
      { key: 'limit', value: '3' },
      { key: 'names', value: '["a.md","b.md"]' },
    ]);
  });

  it('длинный список разворачивается в блоке по строкам', () => {
    const names = Array.from({ length: 20 }, (_, i) => `файл-${i}.md`);
    const args = detectArgumentList(JSON.stringify({ names }));
    // Развёрнутый JSON: скобки сверху и снизу плюс строка на элемент.
    expect(args.blocks[0].lines).toHaveLength(22);
  });
});

describe('detectArgumentList — когда обзора нет', () => {
  it('без аргументов, не объект и не JSON', () => {
    expect(detectArgumentList('{}')).toBeNull();
    expect(detectArgumentList('[1,2]')).toBeNull();
    expect(detectArgumentList('"строка"')).toBeNull();
    expect(detectArgumentList('не json вовсе')).toBeNull();
    expect(detectArgumentList('')).toBeNull();
    expect(detectArgumentList(null)).toBeNull();
  });
});
