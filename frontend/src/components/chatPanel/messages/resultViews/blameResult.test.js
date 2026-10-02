import { detectBlameResult } from './blameResult';
import { detectResultView, parseResult } from './registry';

// Данные — форма настоящего ответа getBlame: ToolResult вокруг GitFileBlame,
// пустые поля Jackson не печатает (у незакоммиченного ханка нет ничего о коммите).

const HASH = 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0';

const hunk = (fromLine, lineCount, extra = {}) => ({
  fromLine,
  lineCount,
  hash: HASH,
  author: 'Alice',
  date: '2024-03-01T10:00:00+03:00',
  summary: 'Fix parser',
  path: 'src/Foo.java',
  sourceLine: fromLine,
  ...extra,
});

const answer = (result, truncated) =>
  JSON.stringify({ project: 'billing', result, ...(truncated === undefined ? {} : { truncated }) });

const detect = (text) => detectBlameResult(parseResult(text));

describe('detectBlameResult — что попадает в «Обзор»', () => {
  it('ответ инструмента забирает вид blame, а не content', () => {
    const text = answer({ path: 'src/Foo.java', lineCount: 40, hunks: [hunk(1, 2)], fromLine: 1, toLine: 2 }, false);
    expect(detectResultView(text).id).toBe('blame');
  });

  it('строка ханка: номера, короткий хеш, ссылка на строки в снимке того коммита', () => {
    const data = detect(
      answer({
        path: 'src/Foo.java',
        lineCount: 40,
        hunks: [hunk(12, 7, { path: 'src/old/Foo.java', sourceLine: 3 })],
        fromLine: 10,
        toLine: 20,
      }),
    );
    expect(data.range).toEqual({ from: 10, to: 20, empty: false });
    expect(data.rows[0]).toMatchObject({ lines: '12–18', shortHash: 'a1b2c3d', author: 'Alice', uncommitted: false });
    expect(data.rows[0].href).toBe(`/files/src/old/Foo.java?project=billing&rev=${HASH}&lines=3-9&right=commit`);
    expect(data.href).toBe('/files/src/Foo.java?project=billing&lines=10-20');
  });

  it('ханк без хеша — незакоммиченные строки; одна строка — без диапазона', () => {
    const data = detect(answer({ path: 'a.txt', lineCount: 3, hunks: [{ fromLine: 3, lineCount: 1 }] }));
    expect(data.rows[0]).toEqual({ key: 'hunk-0', lines: '3', uncommitted: true });
    expect(data.range).toBeNull();
  });

  it('снимок коммита и усечение доходят до вида', () => {
    const data = detect(
      answer({ path: 'a.txt', commit: HASH, lineCount: 300, hunks: [hunk(1, 1)], fromLine: 1, toLine: 1 }, true),
    );
    expect(data.shortRev).toBe('a1b2c3d');
    expect(data.truncated).toBe(true);
    expect(data.href).toContain(`rev=${HASH}`);
  });

  it('диапазон за концом файла — пустой ответ, а не чужой вид', () => {
    const data = detect(answer({ path: 'a.txt', lineCount: 3, hunks: [], fromLine: 10, toLine: 9 }));
    expect(data.range.empty).toBe(true);
    expect(data.rows).toEqual([]);
  });

  it('объект не той формы вид не берёт', () => {
    expect(detect(answer({ path: 'a.txt', lineCount: 3, hunks: [{ fromLine: 0, lineCount: 1 }] }))).toBeNull();
    expect(detect(answer({ path: 'a.txt', hunks: [] }))).toBeNull();
    expect(detect(JSON.stringify([hunk(1, 1)]))).toBeNull();
  });
});
