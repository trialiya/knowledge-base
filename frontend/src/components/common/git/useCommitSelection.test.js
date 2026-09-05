import { renderHook, act } from '@testing-library/react';
import useCommitSelection from './useCommitSelection';

const change = (path, status = 'M') => ({ path, status });

describe('useCommitSelection', () => {
  /** Неотслеживаемый файл в коммит сам не попадёт — и по умолчанию не отмечен. */
  test('everything tracked starts ticked, untracked does not', () => {
    const { result } = renderHook(() => useCommitSelection([change('a.js'), change('report.html', 'U')]));

    expect(result.current.picked).toEqual(['a.js']);
    expect(result.current.total).toBe(2);
  });

  /**
   * Список перечитывается на каждую правку ассистента и на время запроса пуст.
   * Решения человека это переживают: иначе снятая галочка возвращалась бы сама,
   * и следующая попытка коммита унесла бы ровно то, что из него исключили.
   */
  test('a refresh does not tick back what the human unticked', () => {
    const first = [change('a.js'), change('b.js')];
    const { result, rerender } = renderHook(({ entries }) => useCommitSelection(entries), {
      initialProps: { entries: first },
    });

    act(() => result.current.toggle(['b.js'], false));
    expect(result.current.picked).toEqual(['a.js']);

    // Так выглядит перезапрос: сначала пусто, потом тот же список новым массивом.
    rerender({ entries: [] });
    rerender({ entries: [change('a.js'), change('b.js')] });

    expect(result.current.picked).toEqual(['a.js']);
  });

  /** Файл, появившийся после открытия окна, берёт своё значение по умолчанию. */
  test('a file that appears later gets the default, not a neighbour choice', () => {
    const { result, rerender } = renderHook(({ entries }) => useCommitSelection(entries), {
      initialProps: { entries: [change('a.js')] },
    });

    act(() => result.current.toggle(['a.js'], false));
    rerender({ entries: [change('a.js'), change('b.js'), change('out.log', 'U')] });

    expect(result.current.picked).toEqual(['b.js']);
  });

  /** Галочка каталога — состояние группы: всё, часть или ничего. */
  test('a group reports all, some or none', () => {
    const { result } = renderHook(() => useCommitSelection([change('src/a.js'), change('src/b.js')]));
    const group = ['src/a.js', 'src/b.js'];

    expect(result.current.stateOf(group)).toBe('all');
    act(() => result.current.toggle(['src/a.js'], false));
    expect(result.current.stateOf(group)).toBe('some');
    act(() => result.current.toggle(group, false));
    expect(result.current.stateOf(group)).toBe('none');
  });
});
