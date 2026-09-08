import { buildMatcher, collectMatchRanges } from './useFindMatches';

/** DOM с текстом, разложенным по отдельным узлам — как строки файла в CodeView. */
function tree(...lines) {
  const root = document.createElement('div');
  for (const line of lines) {
    const el = document.createElement('code');
    el.textContent = line;
    root.appendChild(el);
  }
  document.body.appendChild(root);
  return root;
}

const found = (root, query, regex) => collectMatchRanges(root, buildMatcher(query, regex)).map((r) => r.toString());

afterEach(() => {
  document.body.innerHTML = '';
});

describe('buildMatcher', () => {
  /** `git grep` зовётся с `-i`, и подсветка обязана находить ровно то же. */
  test('обычный запрос ищется без учёта регистра', () => {
    expect(found(tree('Needle here', 'needle again'), 'NEEDLE')).toEqual(['Needle', 'needle']);
  });

  /** Иначе запрос `a.b` в поиске по подстроке подсвечивал бы `axb`. */
  test('обычный запрос не является выражением', () => {
    expect(found(tree('a.b', 'axb'), 'a.b')).toEqual(['a.b']);
  });

  test('регулярный запрос ищется как выражение', () => {
    expect(found(tree('log.debug("x")', 'log.info("y")'), 'log\\.(debug|info)', true)).toEqual([
      'log.debug',
      'log.info',
    ]);
  });

  /**
   * У `git grep -E` синтаксис POSIX ERE, а не JS: выражение, которое git принял,
   * здесь может не скомпилироваться. Файл всё равно открыт — подсветки просто нет.
   */
  test('невалидное для JS выражение не даёт совпадений и не бросает', () => {
    expect(buildMatcher('needle(', true)).toBeNull();
    expect(found(tree('needle('), 'needle(', true)).toEqual([]);
  });

  test('пустой запрос совпадений не даёт', () => {
    expect(buildMatcher('   ', false)).toBeNull();
  });
});

describe('collectMatchRanges', () => {
  /** Выражение, совпадающее с пустотой, не должно зациклить обход. */
  test('пустое совпадение пропускается, а не крутится вечно', () => {
    expect(found(tree('aaa'), 'b*', true)).toEqual([]);
    expect(found(tree('abc'), 'b*', true)).toEqual(['b']);
  });

  test('совпадения идут в порядке документа', () => {
    expect(found(tree('one needle', 'two needle needle'), 'needle')).toHaveLength(3);
  });

  /** Иначе бар находил бы собственный счётчик и текст своих кнопок. */
  test('текст самого бара в поиск не попадает', () => {
    const root = tree('needle in the file');
    const bar = document.createElement('div');
    bar.setAttribute('data-find-bar', '');
    bar.textContent = 'needle';
    root.prepend(bar);

    expect(found(root, 'needle')).toEqual(['needle']);
  });

  /** Строка файла — отдельный текстовый узел, и `git grep` тоже матчит построчно. */
  test('совпадение через границу строк не находится', () => {
    expect(found(tree('foo', 'bar'), 'foobar')).toEqual([]);
  });
});
