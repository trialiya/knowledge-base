import { formatLines, parseLines } from './urlScheme';

describe('?lines=', () => {
  test('одна строка — номер, несколько — диапазон', () => {
    expect(formatLines(7)).toBe('7');
    expect(formatLines(7, 1)).toBe('7');
    expect(formatLines(7, 3)).toBe('7-9');
  });

  test('читается обратно', () => {
    expect(parseLines('7')).toEqual({ from: 7, to: 7 });
    expect(parseLines(formatLines(7, 3))).toEqual({ from: 7, to: 9 });
  });

  // Адрес правят руками: всё, что не диапазон строк, не выделяет ничего.
  test.each(['', null, '0', '9-3', 'abc', '3-', '-3', '1.5'])('«%s» — не диапазон', (value) => {
    expect(parseLines(value)).toBeNull();
  });
});
