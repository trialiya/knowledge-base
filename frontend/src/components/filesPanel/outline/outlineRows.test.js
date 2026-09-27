import { outlineRows } from './outlineRows';

const sym = (kind, name, startLine, endLine) => ({ kind, name, startLine, endLine });
const shape = (rows) => rows.map(({ symbol, depth }) => [symbol.name, depth]);

describe('outlineRows', () => {
  test('метод внутри класса — по строкам, соседний класс снова на верхнем уровне', () => {
    const rows = outlineRows([
      sym('class', 'A', 1, 20),
      sym('constructor', 'A', 3, 5),
      sym('method', 'run', 7, 19),
      sym('class', 'B', 22, 30),
    ]);
    expect(shape(rows)).toEqual([
      ['A', 0],
      ['A', 1],
      ['run', 1],
      ['B', 0],
    ]);
  });

  test('символ в одну строку ничего не содержит', () => {
    const rows = outlineRows([sym('table', 'users', 1, 1), sym('index', 'users_idx', 1, 1), sym('table', 'b', 2, 2)]);
    expect(shape(rows)).toEqual([
      ['users', 0],
      ['users_idx', 0],
      ['b', 0],
    ]);
  });

  test('markdown: пропуск уровня — одна ступенька, преамбула ничего не содержит', () => {
    const rows = outlineRows([
      sym('preamble', '_preamble', 1, 2),
      sym('h1', 'Гайд', 3, 10),
      sym('h3', 'Docker', 5, 10),
      sym('h1', 'FAQ', 11, 12),
    ]);
    expect(shape(rows)).toEqual([
      ['_preamble', 0],
      ['Гайд', 0],
      ['Docker', 1],
      ['FAQ', 0],
    ]);
  });
});
