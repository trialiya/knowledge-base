import { render } from '@testing-library/react';
import { DiffLines } from './diffRender';

// Раскраска одна на чат и на модалку вызова, и ошибиться в ней значит ошибиться
// в обоих местах сразу.

/** Классы строк патча без общего префикса: add | del | hunk | meta | ctx. */
const kinds = (patch) => {
  const { container } = render(
    <pre>
      <DiffLines patch={patch} />
    </pre>,
  );
  return [...container.querySelectorAll('span')].map(
    (el) => el.className.split(' ')[1]?.replace('diff-line--', '') ?? 'ctx',
  );
};

describe('DiffLines', () => {
  it('шапка патча — не изменение', () => {
    const patch = ['diff --git a/a.js b/a.js', 'index 1a2b3c4..5d6e7f8 100644', '--- a/a.js', '+++ b/a.js'].join('\n');
    expect(kinds(patch)).toEqual(['meta', 'meta', 'meta', 'meta']);
  });

  it('внутри ханка строка, начинающаяся с дефисов, — удаление, а не имя файла', () => {
    // `-- ` в начале удаляемой строки (SQL-комментарий, markdown-разделитель)
    // даёт `--- `, и до сверки с позицией это красилось шапкой.
    const patch = ['@@ -1,3 +1,3 @@', '-- комментарий', '+++ добавили', ' контекст'].join('\n');
    expect(kinds(patch)).toEqual(['hunk', 'del', 'add', 'ctx']);
  });

  it('патч на несколько файлов: со следующего diff --git снова шапка', () => {
    const patch = [
      '@@ -1 +1 @@',
      '-a',
      'diff --git a/b.js b/b.js',
      '--- a/b.js',
      '+++ b/b.js',
      '@@ -1 +1 @@',
      '+b',
    ].join('\n');
    expect(kinds(patch)).toEqual(['hunk', 'del', 'meta', 'meta', 'meta', 'hunk', 'add']);
  });
});
