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
  return [...container.querySelectorAll('.diff-line')].map(
    (el) => el.className.split(' ')[1]?.replace('diff-line--', '') ?? 'ctx',
  );
};

/** Номера строк в гуттере; пустая строка — номер этой строке не положен. */
const numbers = (patch) => {
  const { container } = render(
    <pre>
      <DiffLines patch={patch} lineNumbers />
    </pre>,
  );
  return [...container.querySelectorAll('.diff-line__no')].map((el) => el.textContent);
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

  it('номера идут от заголовка ханка: у удалённой строки — из старого файла, у остальных — из нового', () => {
    const patch = ['@@ -40,3 +50,4 @@ class A {', ' контекст', '-было', '+стало', '+ещё', ' хвост'].join('\n');
    expect(numbers(patch)).toEqual(['', '50', '41', '51', '52', '53']);
  });

  it('шапке, примечанию про перевод строки и хвостовой пустой строке номера не положено', () => {
    // `split('\n')` на патче с завершающим переносом даёт лишний элемент —
    // строкой файла он никогда не был, и нумеровать его значит сдвинуть счёт.
    const patch = ['--- a/a.js', '+++ b/a.js', '@@ -1 +1 @@', '-a', '\\ No newline at end of file', '+b', ''].join(
      '\n',
    );
    expect(numbers(patch)).toEqual(['', '', '', '1', '', '1']);
  });

  it('патч на несколько файлов: со следующего ханка отсчёт начинается заново', () => {
    const patch = ['@@ -1 +7 @@', '+a', 'diff --git a/b.js b/b.js', '@@ -1 +100 @@', '+b'].join('\n');
    expect(numbers(patch)).toEqual(['', '7', '', '', '100']);
  });
});
