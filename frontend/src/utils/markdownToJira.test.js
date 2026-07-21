import { markdownToJira } from './markdownToJira';

describe('markdownToJira', () => {
  it('пустой вход даёт пустую строку', () => {
    expect(markdownToJira('')).toBe('');
    expect(markdownToJira(undefined)).toBe('');
  });

  it('заголовки h1-h6', () => {
    expect(markdownToJira('# A\n## B\n###### F')).toBe('h1. A\nh2. B\nh6. F');
  });

  it('инлайн-форматирование: жирный, курсив, зачёркнутый, код', () => {
    expect(markdownToJira('**bold** and _italic_ and ~~gone~~ and `code`')).toBe(
      '*bold* and _italic_ and -gone- and {{code}}',
    );
  });

  it('жирный на **, курсив на одиночных * не путаются друг с другом', () => {
    expect(markdownToJira('**bold** then *italic*')).toBe('*bold* then _italic_');
  });

  it('вложенный инлайн-код внутри жирного текста восстанавливается полностью', () => {
    expect(markdownToJira('**`code`** and **bold**')).toBe('*{{code}}* and *bold*');
  });

  it('фигурные скобки внутри инлайн-кода экранируются, чтобы не сливаться с {{ }}', () => {
    expect(markdownToJira('чистые значения без обёртки `{"string": ...}`')).toBe(
      'чистые значения без обёртки {{\\{"string": ...\\}}}',
    );
    expect(markdownToJira('`{}`')).toBe('{{\\{\\}}}');
  });

  it('круглые скобки внутри инлайн-кода в таблице экранируются (иначе Jira видит смайлик)', () => {
    const md = '| URN |\n| --- |\n| `urn:li:dataset:(...)` |';
    expect(markdownToJira(md)).toBe('||URN||\n|{{urn:li:dataset:\\(...\\)}}|');
  });

  it('обратный слэш перед скобкой в коде тоже экранируется (иначе он "съедает" экранирование скобки)', () => {
    // code content is literally backslash + "{" (2 chars). If we only escaped
    // "{" and left the backslash alone, the result would be "\\{" (backslash,
    // backslash, brace), which an escape-aware reader would parse as an
    // escaped backslash followed by a bare, unescaped "{" -- reintroducing
    // the delimiter-ambiguity bug. Escaping the backslash too keeps it literal.
    expect(markdownToJira('`\\{`')).toBe('{{' + '\\'.repeat(3) + '{}}');
  });

  it('ссылки и изображения', () => {
    expect(markdownToJira('[docs](https://example.com)')).toBe('[docs|https://example.com]');
    expect(markdownToJira('![alt text](https://example.com/img.png)')).toBe(
      '!https://example.com/img.png|alt=alt text!',
    );
    expect(markdownToJira('![](https://example.com/img.png)')).toBe('!https://example.com/img.png!');
  });

  it('внутренние ссылки на файлы репозитория заменяются на "имя (путь)", а не на гиперссылку', () => {
    expect(markdownToJira('[`backend/build.gradle`](/files?path=backend/build.gradle)')).toBe(
      'build.gradle (backend/build.gradle)',
    );
    expect(markdownToJira('[GitService.java](/files?path=backend/src/main/java/GitService.java)')).toBe(
      'GitService.java (backend/src/main/java/GitService.java)',
    );
  });

  it('внутренние ссылки на документы базы знаний заменяются на обычный текст', () => {
    expect(markdownToJira('[Модели данных](/?doc=42)')).toBe('Модели данных');
  });

  it('внешние ссылки, даже содержащие "doc=" не по внутреннему пути, остаются гиперссылками', () => {
    expect(markdownToJira('[external](https://external-site/?doc=42)')).toBe(
      '[external|https://external-site/?doc=42]',
    );
  });

  it('маркированный и нумерованный списки, включая вложенность', () => {
    expect(markdownToJira('- one\n- two\n  - nested')).toBe('* one\n* two\n** nested');
    expect(markdownToJira('1. one\n2. two')).toBe('# one\n# two');
  });

  it('цитата: одна строка -> bq., несколько строк -> {quote}', () => {
    expect(markdownToJira('> single line')).toBe('bq. single line');
    expect(markdownToJira('> line one\n> line two')).toBe('{quote}\nline one\nline two\n{quote}');
  });

  it('блок кода с языком и без', () => {
    expect(markdownToJira('```js\nconst x = 1;\n```')).toBe('{code:javascript}\nconst x = 1;\n{code}');
    expect(markdownToJira('```\nplain\n```')).toBe('{code}\nplain\n{code}');
  });

  it('горизонтальная линия', () => {
    expect(markdownToJira('---')).toBe('----');
    expect(markdownToJira('***')).toBe('----');
  });

  it('таблица GFM конвертируется в таблицу Jira', () => {
    const md = '| Col1 | Col2 |\n| --- | --- |\n| a | b |\n| c | d |';
    expect(markdownToJira(md)).toBe('||Col1||Col2||\n|a|b|\n|c|d|');
  });

  it('обычный текст без разметки остаётся как есть', () => {
    expect(markdownToJira('just plain text')).toBe('just plain text');
  });
});
