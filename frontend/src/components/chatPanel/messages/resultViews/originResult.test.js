import { detectOriginResult } from './originResult';
import { detectResultView, parseResult } from './registry';

// Данные — форма настоящего ответа getLineOrigin: ToolResult вокруг GitLineOrigin,
// шаги от новой версии к старой, пустые поля Jackson не печатает.

const NEW = 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0';
const OLD = 'e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3';

const step = (hash, line, text, extra = {}) => ({
  hash,
  author: 'Alice',
  date: '2024-03-01T10:00:00+03:00',
  summary: 'Add total',
  path: 'src/Foo.java',
  line,
  text,
  ...extra,
});

const answer = (result) => JSON.stringify({ project: 'billing', result });

const found = {
  path: 'src/Foo.java',
  line: 12,
  query: 'total',
  status: 'FOUND',
  steps: [step(NEW, 12, 'final int x = total();'), step(OLD, 10, 'int x = total();', { path: 'src/old/Foo.java' })],
  before: step(OLD, 10, 'int x = 1;', { hash: 'c'.repeat(40) }),
};

const detect = (text) => detectOriginResult(parseResult(text));

describe('detectOriginResult — что попадает в «Обзор»', () => {
  it('ответ инструмента забирает вид origin, а не content', () => {
    expect(detectResultView(answer(found)).id).toBe('origin');
  });

  it('ответ — последний шаг; ссылка ведёт к строке в снимке того коммита, с запросом', () => {
    const data = detect(answer(found));
    expect(data.kind).toBe('found');
    expect(data.origin).toMatchObject({ shortHash: 'e4f5a6b', boundary: false });
    expect(data.origin.href).toBe(`/files/src/old/Foo.java?project=billing&rev=${OLD}&find=total&lines=10&right=commit`);
    expect(data.before.text).toBe('int x = 1;');
    expect(data.versions).toHaveLength(2);
    expect(data.href).toBe('/files/src/Foo.java?project=billing&find=total&lines=12');
  });

  it('шаг на границе клона несёт флаг', () => {
    const data = detect(
      answer({ ...found, status: 'BOUNDARY', steps: [step(OLD, 1, 'x total', { author: undefined, boundary: true })] }),
    );
    expect(data.kind).toBe('boundary');
    expect(data.origin.boundary).toBe(true);
  });

  it('незакоммиченная правка: ответа-коммита нет, «было» — строка в HEAD', () => {
    const data = detect(answer({ ...found, status: 'UNCOMMITTED', steps: [] }));
    expect(data.kind).toBe('uncommitted');
    expect(data.origin).toBeNull();
    expect(data.before).not.toBeNull();
  });

  it('объект не той формы вид не берёт', () => {
    expect(detect(answer({ ...found, query: '' }))).toBeNull();
    expect(detect(answer({ ...found, steps: [{ hash: NEW }] }))).toBeNull();
    expect(detect(answer({ ...found, before: { text: 'x' } }))).toBeNull();
    expect(detect(answer({ path: 'a.txt', lineCount: 3, hunks: [] }))).toBeNull();
  });
});
