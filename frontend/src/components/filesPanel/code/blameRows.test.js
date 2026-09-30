import { blameRows } from './blameRows';

const hunk = (fromLine, lineCount, hash = 'h') => ({ fromLine, lineCount, hash });

describe('blameRows', () => {
  test('одна ячейка на ханк, растянутая на его строки', () => {
    const rows = blameRows([hunk(1, 2, 'a'), hunk(3, 1, 'b')], 1, 3);

    expect(rows).toEqual([{ hunk: hunk(1, 2, 'a'), span: 2 }, null, { hunk: hunk(3, 1, 'b'), span: 1 }]);
  });

  // Ответа ещё нет — колонка стоит пустой, по ячейке на строку.
  test('без ханков каждая строка получает пустую ячейку', () => {
    expect(blameRows([], 1, 2)).toEqual([
      { hunk: null, span: 1 },
      { hunk: null, span: 1 },
    ]);
  });

  // Диапазонное чтение начинается посреди ханка: ячейка начинается с первой
  // показанной строки и тянется до конца ханка, но не дальше показанного.
  test('ханк обрезается по показанному диапазону', () => {
    const rows = blameRows([hunk(1, 10, 'a'), hunk(11, 5, 'b')], 8, 5);

    expect(rows).toEqual([{ hunk: hunk(1, 10, 'a'), span: 3 }, null, null, { hunk: hunk(11, 5, 'b'), span: 2 }, null]);
  });

  // Строк в тексте больше, чем в ответе (правка между двумя запросами): хвост
  // остаётся без подписи, но со своей ячейкой.
  test('строки за последним ханком получают пустые ячейки', () => {
    expect(blameRows([hunk(1, 1)], 1, 2)).toEqual([
      { hunk: hunk(1, 1), span: 1 },
      { hunk: null, span: 1 },
    ]);
  });
});
