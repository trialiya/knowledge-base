import { collapseCrumbs } from './breadcrumbs';

const item = (key) => ({ key, label: key, onNavigate: () => {} });

describe('collapseCrumbs', () => {
  it('цепочку без середины возвращает той же самой — прятать нечего', () => {
    const items = [item('root'), item('file')];
    expect(collapseCrumbs(items)).toBe(items);
    // Та же цепочка глазами файлового раздела (keepEnd = 2: папка + файл).
    expect(collapseCrumbs([...items, item('x')], 1, 2)).toHaveLength(3);
  });

  it('схлопывает и единственное скрытое звено: решение о схлопывании принято по ширине', () => {
    // Длинное название папки из базы знаний экономит строке больше, чем стоит
    // потерянная ссылка на неё, — иначе HeadCrumbs сюда бы и не обратился.
    const items = [item('root'), item('a'), item('file')];
    const result = collapseCrumbs(items);

    expect(result).toHaveLength(3);
    expect(result[1]).toMatchObject({ label: '…', ellipsis: true, title: 'a' });
  });

  it('схлопывает середину, оставляя края нетронутыми', () => {
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
