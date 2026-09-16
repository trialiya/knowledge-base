import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { cases } from './harness/registry';

/**
 * Реестр стенда (`harness/registry.jsx`) и реестр кейсов (`cases.yaml`) описывают
 * одно и то же и связаны одним именем: шапка реестра стенда обещает, что `id`
 * записи совпадает со ссылкой на фикстуру в кейсе — `<модуль>#<экспорт>`.
 * Обещание ничем не проверялось: переименованный экспорт, опечатка в ссылке,
 * заведённая запись стенда, которую ни один кейс не описывает, — всё это молча
 * расходится, а замечают расхождение, когда ищут снимок по кейсу и не находят.
 *
 * Здесь сверяются обе стороны. Ссылку на фикстуру из кейса проверяем по самим
 * фикстурам, а не по реестру стенда: часть кейсов снята на живом бэкенде
 * (`./run/test.sh smoke`), стенду они не по зубам, и записи у них нет — но
 * фикстура, на которую кейс ссылается, существовать обязана.
 *
 * Сверка идёт по фикстуре, а не по `id` целиком: вариант через `@` — это шаг
 * стенда (та же фикстура, открытое меню, другая вкладка), и кейс называет
 * фикстуру, а не каждый снимок с ней. Тёмные двойники (`@dark`) по той же
 * причине не перечислены нигде: их заводит сам реестр каждому светлому кейсу —
 * см. `theme-dark-sweep`.
 */
const HERE = dirname(fileURLToPath(import.meta.url));

/** Экспорты каждого модуля фикстур: `<модуль>.js` → набор имён. */
const fixtureExports = (() => {
  const modules = import.meta.glob('./fixtures/*.js', { eager: true });
  const out = new Map();
  for (const [path, module] of Object.entries(modules)) {
    out.set(path.slice('./fixtures/'.length), new Set(Object.keys(module)));
  }
  return out;
})();

/**
 * Ссылки на фикстуры из `cases.yaml` — обе формы записи: `fixtures: <ссылка>` и
 * список под ключом. Разборщика YAML в зависимостях нет (см. cases.test.js),
 * поэтому построчно; форма ссылки проверяется отдельным тестом, так что
 * неразобранное сюда не проваливается молча.
 */
function fixtureRefs() {
  const lines = readFileSync(join(HERE, 'cases.yaml'), 'utf8').split('\n');
  const refs = [];
  lines.forEach((line, i) => {
    const inline = line.match(/^\s*fixtures:\s*(\S.*)$/);
    if (inline) {
      refs.push({ ref: inline[1].trim(), line: i + 1 });
      return;
    }
    const key = line.match(/^( *)fixtures:\s*$/);
    if (!key) return;
    // Список кончается по отступу, а не по «первой строке, которая не элемент»:
    // элемент кейса (`- id: …`) тоже начинается с дефиса, и разбор по виду
    // строки утащил бы в ссылки следующий кейс — стоит `fixtures` оказаться
    // последним ключом. Пустая строка и комментарий внутри списка его не
    // кончают: на них разбор терял бы хвост молча.
    //
    // Отступ элемента берём у первого из них, а не задаём: YAML разрешает
    // списку стоять и вровень с ключом, и такой блок при жёстком «ключ + 2»
    // не дал бы ни одной ссылки — тоже молча. Своего отступа у ключа элемент
    // при этом быть не может меньше: `- id:` соседнего кейса как раз левее.
    let inner = null;
    for (let j = i + 1; j < lines.length; j += 1) {
      if (/^\s*(#.*)?$/.test(lines[j])) continue;
      const item = lines[j].match(/^( *)- (.+)$/);
      if (!item || item[1].length < key[1].length) break;
      if (inner === null) inner = item[1].length;
      if (item[1].length !== inner) break;
      refs.push({ ref: item[2].trim(), line: j + 1 });
    }
  });
  return refs;
}

/** `./fixtures/x.js#a` и `x.js#a@шаг` — к одному виду: `x.js`, `a`. */
function parse(ref) {
  const bare = ref.replace(/^\.\/fixtures\//, '');
  const [module, rest] = bare.split('#');
  return { module, name: rest?.split('@')[0] };
}

/** Общее имя фикстуры у кейса и у записи стенда — без шага и без темы. */
function fixtureOf(ref) {
  const { module, name } = parse(ref);
  return `${module}#${name}`;
}

const at = ({ ref, line }) => `cases.yaml:${line} — ${ref}`;

describe('реестр стенда', () => {
  it('не заводит двух записей с одним id', () => {
    const seen = new Set();
    const twice = cases.map((c) => c.id).filter((id) => (seen.has(id) ? true : (seen.add(id), false)));
    expect(twice).toEqual([]);
  });

  it('каждой записью находит свою фикстуру', () => {
    expect(cases.filter((c) => c.missing).map((c) => c.id)).toEqual([]);
  });

  it('не показывает того, чего не описывает ни один кейс', () => {
    const described = new Set(fixtureRefs().map(({ ref }) => fixtureOf(ref)));
    const undescribed = cases.filter((c) => !c.theme && !described.has(fixtureOf(c.id))).map((c) => c.id);
    expect(undescribed).toEqual([]);
  });
});

describe('реестр кейсов', () => {
  it('ссылается на фикстуры в одной форме: - ./fixtures/<модуль>.js#<экспорт>', () => {
    // Форма ровно та, что описана в шапке cases.yaml, без поблажек: пока
    // сторож принимал и скаляр после ключа, и ссылку без префикса, в файле
    // сосуществовали три записи одного и того же — а разбирать их приходится
    // всем, кто читает файл машиной.
    const lines = readFileSync(join(HERE, 'cases.yaml'), 'utf8').split('\n');
    const scalar = lines
      .map((text, i) => ({ text, line: i + 1 }))
      .filter(({ text }) => /^\s*fixtures:\s*\S/.test(text))
      .map(({ text, line }) => `cases.yaml:${line} — ${text.trim()} (ссылки идут списком, а не после ключа)`);
    const odd = fixtureRefs()
      .filter(({ ref }) => !/^\.\/fixtures\/[\w.-]+\.js#\w+(@[\w-]+)*$/.test(ref))
      .map(at);
    expect([...scalar, ...odd]).toEqual([]);
  });

  it('называет ключ со ссылками одинаково во всех кейсах', () => {
    // Два написания ключа читаются одинаково, а находятся по-разному: кейсы с
    // `fixture:` выпадали из сверки целиком, и стенд у них расходился молча.
    const lines = readFileSync(join(HERE, 'cases.yaml'), 'utf8').split('\n');
    const singular = lines.map((line, i) => ({ line: i + 1, text: line })).filter(({ text }) => /^\s*fixture:\s/.test(text));
    expect(singular.map(({ line, text }) => `cases.yaml:${line} — ${text.trim()}`)).toEqual([]);
  });

  it('не ссылается на фикстуру, которой нет', () => {
    const broken = fixtureRefs().filter((entry) => {
      const { module, name } = parse(entry.ref);
      return !fixtureExports.get(module)?.has(name);
    });
    expect(broken.map(at)).toEqual([]);
  });
});
