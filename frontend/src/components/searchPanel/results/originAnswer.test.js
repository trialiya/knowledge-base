import { describeOrigin, originKey } from './originAnswer';

// Данные — форма GitLineOrigin с бэкенда: steps от новой версии к старой.

const step = (hash, text) => ({ hash, author: 'Alice', path: 'a.txt', line: 3, text });

describe('describeOrigin', () => {
  it('FOUND: ответ — последний шаг, «было» — версия до него', () => {
    const steps = [step('c3', 'final x = total()'), step('c2', 'x = total()')];
    const view = describeOrigin({ status: 'FOUND', steps, before: step('c1', 'x = 1') });
    expect(view.kind).toBe('found');
    expect(view.origin.hash).toBe('c2');
    expect(view.before.hash).toBe('c1');
    expect(view.path).toHaveLength(2);
  });

  it('граница клона и предел шагов называют самый старый пройденный шаг', () => {
    expect(describeOrigin({ status: 'BOUNDARY', steps: [step('c1', 'x')] })).toMatchObject({
      kind: 'boundary',
      origin: { hash: 'c1' },
    });
    expect(describeOrigin({ status: 'LIMIT', steps: [step('c9', 'x'), step('c8', 'x')] }).origin.hash).toBe('c8');
  });

  it('без шагов ответа нет: незакоммиченная строка, подстроки нет, непонятный статус', () => {
    expect(describeOrigin({ status: 'UNCOMMITTED', steps: [] })).toMatchObject({ kind: 'uncommitted', origin: null });
    // У незакоммиченной правки «было» остаётся — строка в HEAD, которую она заменила.
    expect(describeOrigin({ status: 'UNCOMMITTED', steps: [], before: step('h', 'x') }).before.hash).toBe('h');
    expect(describeOrigin({ status: 'NOT_IN_LINE', steps: [] })).toMatchObject({ kind: 'notInLine', origin: null });
    expect(describeOrigin({ status: 'SOMETHING_NEW' }).origin).toBeNull();
  });
});

it('originKey различает ревизию, проект, строку и запрос', () => {
  const base = { path: 'a.txt', line: 3, query: 'x', rev: '', project: '' };
  const keys = new Set(
    [base, { ...base, rev: 'v1' }, { ...base, project: 'p' }, { ...base, line: 4 }, { ...base, query: 'y' }].map(
      originKey,
    ),
  );
  expect(keys.size).toBe(5);
});
