import { findSectionHeading, normalizeTitle } from './sectionAnchor';

function preview(html) {
  const root = document.createElement('div');
  root.innerHTML = html;
  return root;
}

describe('normalizeTitle', () => {
  test('срезает inline-разметку markdown и схлопывает пробелы', () => {
    expect(normalizeTitle('**Docker** и `kb.tools`')).toBe('Docker и kb.tools');
    expect(normalizeTitle('[Установка](/?doc=5)  \n через  compose')).toBe('Установка через compose');
  });
});

describe('findSectionHeading', () => {
  const root = preview(`
    <h1 id="a">Установка</h1><p>вступление</p>
    <h2>Docker</h2><p>раз</p>
    <h2>Вручную</h2>
    <h3>Linux</h3>
    <h1>FAQ</h1>
    <h2>Вопрос</h2>
    <h2>Вопрос</h2>
  `);

  test('находит по цепочке заголовков-предков', () => {
    expect(findSectionHeading(root, 'Установка > Docker').textContent).toBe('Docker');
    expect(findSectionHeading(root, 'Установка > Вручную > Linux').textContent).toBe('Linux');
  });

  test('повторный путь различает суффикс занятости', () => {
    const second = findSectionHeading(root, 'FAQ > Вопрос[2]');
    const first = findSectionHeading(root, 'FAQ > Вопрос');
    expect(first).not.toBe(second);
    expect(root.querySelectorAll('h2')[3]).toBe(second);
  });

  test('заголовок с разметкой на стороне бэкенда сходится с отрендеренным', () => {
    const r = preview('<h1>Установка</h1><h2><strong>Docker</strong> и <code>compose</code></h2>');
    expect(findSectionHeading(r, 'Установка > **Docker** и `compose`')).toBe(r.querySelector('h2'));
  });

  test('преамбула, пустой и незнакомый путь дают null', () => {
    expect(findSectionHeading(root, '_preamble')).toBeNull();
    expect(findSectionHeading(root, '')).toBeNull();
    expect(findSectionHeading(root, 'Нет такого')).toBeNull();
  });
});
