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

  it('форма без вида — обзора нет вовсе', () => {
    expect(detectResultView('"Done"')).toBeNull();
    expect(detectResultView('')).toBeNull();
  });
});
