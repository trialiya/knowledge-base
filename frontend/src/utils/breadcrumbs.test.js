import { collapseCrumbs } from './breadcrumbs';

const item = (key) => ({ key, label: key, onNavigate: () => {} });

describe('collapseCrumbs', () => {
  it('короткую цепочку не трогает', () => {
    const items = [item('root'), item('a'), item('b')];
    expect(collapseCrumbs(items)).toBe(items);
  });

  it('прячущую единственное звено цепочку не схлопывает — выигрыша нет', () => {
    const items = [item('root'), item('a'), item('file')];
    expect(collapseCrumbs(items)).toEqual(items);
  });

  it('схлопывает середину, когда прячет два и больше звеньев', () => {
    const items = [item('root'), item('a'), item('b'), item('c'), item('file')];
    const result = collapseCrumbs(items);

    expect(result).toHaveLength(3);
    expect(result[0]).toBe(items[0]);
    expect(result[2]).toBe(items[4]);
    expect(result[1]).toMatchObject({ label: '…', ellipsis: true, title: 'a / b / c' });
  });

  it('уважает свои keepStart/keepEnd', () => {
    const items = [item('root'), item('a'), item('b'), item('c'), item('d'), item('file')];
    const result = collapseCrumbs(items, 2, 2);

    expect(result).toHaveLength(5);
    expect(result[0]).toBe(items[0]);
    expect(result[1]).toBe(items[1]);
    expect(result[3]).toBe(items[4]);
    expect(result[4]).toBe(items[5]);
    expect(result[2].title).toBe('b / c');
  });
});
