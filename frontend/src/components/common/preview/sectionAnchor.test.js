import { findSection, normalizeTitle } from './sectionAnchor';

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

describe('findSection', () => {
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
    expect(findSection(root, 'Установка > Docker').from.textContent).toBe('Docker');
    expect(findSection(root, 'Установка > Вручную > Linux').from.textContent).toBe('Linux');
  });

  // Раздел кончается на следующем заголовке не глубже своего: подразделы — его
  // часть, а последний раздел тянется до конца.
  test('конец раздела — следующий заголовок не глубже, у последнего его нет', () => {
    expect(findSection(root, 'Установка > Вручную').to.textContent).toBe('FAQ');
    expect(findSection(root, 'Установка').to.textContent).toBe('FAQ');
    expect(findSection(root, 'Установка > Docker').to.textContent).toBe('Вручную');
    expect(findSection(root, 'FAQ > Вопрос[2]').to).toBeNull();
  });

  test('повторный путь различает суффикс занятости', () => {
    const second = findSection(root, 'FAQ > Вопрос[2]').from;
    const first = findSection(root, 'FAQ > Вопрос').from;
    expect(first).not.toBe(second);
    expect(root.querySelectorAll('h2')[3]).toBe(second);
  });

  // «[2]» в конце может быть и частью заголовка: бэкенд ставит суффикс без
  // пробела, и такой заголовок, встреченный однажды, суффикса не получает.
  test('заголовок, сам кончающийся на [n], не принимается за повтор', () => {
    const r = preview('<h1>Примечание [2]</h1><p>раз</p><h1>Примечание</h1><h1>Примечание</h1>');
    const [own, first, second] = r.querySelectorAll('h1');
    expect(findSection(r, 'Примечание [2]').from).toBe(own);
    expect(findSection(r, 'Примечание').from).toBe(first);
    expect(findSection(r, 'Примечание[2]').from).toBe(second);
  });

  test('заголовок с разметкой на стороне бэкенда сходится с отрендеренным', () => {
    const r = preview('<h1>Установка</h1><h2><strong>Docker</strong> и <code>compose</code></h2>');
    expect(findSection(r, 'Установка > **Docker** и `compose`').from).toBe(r.querySelector('h2'));
  });

  test('преамбула, пустой и незнакомый путь дают null', () => {
    expect(findSection(root, '_preamble')).toBeNull();
    expect(findSection(root, '')).toBeNull();
    expect(findSection(root, 'Нет такого')).toBeNull();
  });
});
