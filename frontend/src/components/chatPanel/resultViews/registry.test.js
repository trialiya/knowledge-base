import { detectResultView, parseResult } from './registry';

// Реестр отвечает за две вещи: разобрать ответ один раз и выбрать первый
// подошедший вид. Что именно каждый вид считает своей формой — в его тестах.

describe('parseResult', () => {
  it('пустой ответ входом не становится', () => {
    expect(parseResult('')).toBeNull();
    expect(parseResult(null)).toBeNull();
    expect(parseResult(undefined)).toBeNull();
  });

  it('не JSON помечается флагом, а не выбрасывается', () => {
    expect(parseResult('просто текст')).toMatchObject({ isJson: false, parsed: null, resultText: 'просто текст' });
    expect(parseResult('{"a":1}')).toMatchObject({ isJson: true, parsed: { a: 1 } });
  });
});

describe('detectResultView', () => {
  it('diff выбирается раньше текста', () => {
    const view = detectResultView(
      JSON.stringify({ operation: 'edit', path: 'a.js', additions: 1, deletions: 1, diff: '@@ -1 +1 @@\n-a\n+b' }),
    );
    expect(view.id).toBe('diff');
  });

  it('текстовый результат достаётся виду content', () => {
    const view = detectResultView(JSON.stringify({ path: 'a.md', content: 'x\n'.repeat(20) }));
    expect(view.id).toBe('content');
  });

  it('список записей — recordList, а список текстов всё равно content', () => {
    const commits = JSON.stringify([{ shortHash: 'abc', author: 'kb', message: 'fix', files: null }]);
    expect(detectResultView(commits).id).toBe('recordList');

    const texts = JSON.stringify([{ id: 1, fileName: 'a.md', content: 'x\n'.repeat(20) }]);
    expect(detectResultView(texts).id).toBe('content');
  });

  it('иерархию забирает tree, даже если запись подошла бы и списком', () => {
    // Пути и ссылки на родителя — иерархия, и вид дерева стоит в реестре выше:
    // ровно так порядок и уточняет уже работающий отбор.
    const files = JSON.stringify([{ path: 'a/b.java', name: 'b.java', type: 'FILE', size: 12 }]);
    expect(detectResultView(files).id).toBe('tree');
  });

  it('совпадения поиска — grepMatches, а не список и не текст', () => {
    const matches = JSON.stringify([{ path: 'a.java', matchLine: 85, text: '-84- a;\n:85: b;\n' }]);
    expect(detectResultView(matches).id).toBe('grepMatches');
  });

  it('короткое значение — scalar', () => {
    expect(detectResultView('"Done"').id).toBe('scalar');
  });

  it('форма без вида — обзора нет вовсе', () => {
    // Одиночная документная мутация: свой вид ещё не написан.
    expect(detectResultView(JSON.stringify({ id: 75, title: 'анализ', type: 'folder', version: 1 }))).toBeNull();
    expect(detectResultView('')).toBeNull();
  });
});
