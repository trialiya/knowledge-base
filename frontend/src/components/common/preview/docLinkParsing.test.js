import { parseCommitLink, parseDocId, parseFileLink } from './docLinkParsing';

/**
 * Разбор внутренних ссылок. Обе формы обязаны пониматься:
 *   • историческая (`/?doc=N`, `/files?path=P`) — она хранится в markdown
 *     документов и в сообщениях чата, её же пишет бэкенд;
 *   • каноническая (`/knowledge/doc/N`, `/files/P`) — её рендерит DocLinkTooltip
 *     как настоящий href и её же строит навигация.
 */
describe('parseDocId', () => {
  it('разбирает историческую форму', () => {
    expect(parseDocId('/?doc=70')).toBe(70);
    expect(parseDocId('?doc=70')).toBe(70);
    expect(parseDocId('/knowledge?doc=70')).toBe(70);
    expect(parseDocId(`${window.location.origin}/?doc=70`)).toBe(70);
  });

  it('разбирает каноническую форму', () => {
    expect(parseDocId('/knowledge/doc/70')).toBe(70);
    expect(parseDocId('/knowledge/doc/70?right=info')).toBe(70);
    expect(parseDocId(`${window.location.origin}/knowledge/doc/70`)).toBe(70);
  });

  it('не трогает внешние ссылки и мусор', () => {
    expect(parseDocId('https://example.com/?doc=70')).toBeNull();
    expect(parseDocId('https://example.com/knowledge/doc/70')).toBeNull();
    expect(parseDocId('/?doc=abc')).toBeNull();
    expect(parseDocId('/knowledge/doc/abc')).toBeNull();
    expect(parseDocId('')).toBeNull();
  });
});

describe('parseFileLink', () => {
  it('разбирает историческую форму, включая диапазон строк', () => {
    expect(parseFileLink('/files?path=backend/pom.xml')).toMatchObject({ path: 'backend/pom.xml' });
    expect(parseFileLink('/files?path=backend/pom.xml#L42')).toMatchObject({
      path: 'backend/pom.xml',
      fromLine: 42,
      toLine: 42,
    });
    expect(parseFileLink('/files?path=backend/pom.xml#L42-L58')).toMatchObject({ fromLine: 42, toLine: 58 });
  });

  it('разбирает каноническую форму с путём в самом пути', () => {
    expect(parseFileLink('/files/backend/pom.xml')).toMatchObject({ path: 'backend/pom.xml' });
    expect(parseFileLink('/files/backend/My%20File.java#L7')).toMatchObject({
      path: 'backend/My File.java',
      fromLine: 7,
      toLine: 7,
    });
  });

  it('не трогает внешние ссылки, чужие пути и пустой путь', () => {
    expect(parseFileLink('https://example.com/files?path=a.md')).toBeNull();
    expect(parseFileLink('/filesystem/a.md')).toBeNull();
    expect(parseFileLink('/files')).toBeNull();
    expect(parseFileLink('/?doc=70')).toBeNull();
  });
});

/**
 * Проект — часть адреса файла: один и тот же путь есть в каждом репозитории, и
 * ссылка, потерявшая проект, откроет файл, который всего лишь совпал путём.
 */
describe('parseFileLink: проект', () => {
  it('читает проект в обеих формах ссылки', () => {
    expect(parseFileLink('/files?path=a/B.java&project=kb')).toEqual({
      project: 'kb',
      path: 'a/B.java',
      rev: null,
      fromLine: null,
      toLine: null,
    });
    expect(parseFileLink('/files/a/B.java?project=kb#L10-L20')).toEqual({
      project: 'kb',
      path: 'a/B.java',
      rev: null,
      fromLine: 10,
      toLine: 20,
    });
  });

  it('ссылка без проекта означает дефолтный — так написаны все старые', () => {
    expect(parseFileLink('/files?path=a/B.java')?.project).toBeNull();
    expect(parseFileLink('/files/a/B.java')?.project).toBeNull();
  });
});

/**
 * Ревизия у ссылки на файл — это версия, которую процитировала модель: без неё
 * превью показало бы сегодняшний файл вместо того, о котором говорит ответ.
 */
describe('parseFileLink: ревизия', () => {
  it('читает ревизию в обеих формах ссылки', () => {
    expect(parseFileLink('/files?path=a/B.java&rev=abc1234&project=kb#L3')).toMatchObject({
      path: 'a/B.java',
      rev: 'abc1234',
      fromLine: 3,
    });
    expect(parseFileLink('/files/a/B.java?rev=abc1234')?.rev).toBe('abc1234');
  });

  it('ссылка без ревизии — рабочее дерево', () => {
    expect(parseFileLink('/files?path=a/B.java')?.rev).toBeNull();
  });
});

describe('parseCommitLink', () => {
  const hash = '0123456789abcdef0123456789abcdef01234567';

  it('разбирает форму, которую пишет модель, и каноническую', () => {
    expect(parseCommitLink(`/files?rev=${hash}&project=kb`)).toEqual({ project: 'kb', hash });
    expect(parseCommitLink(`/files?project=kb&changes=1&rev=${hash}&right=commit`)).toEqual({ project: 'kb', hash });
    expect(parseCommitLink(`/files/?rev=${hash}`)).toEqual({ project: null, hash });
    expect(parseCommitLink(`${window.location.origin}/files?rev=abc1234`)).toEqual({ project: null, hash: 'abc1234' });
  });

  it('ссылка с путём — это файл в коммите, а не коммит', () => {
    expect(parseCommitLink(`/files?path=a.md&rev=${hash}`)).toBeNull();
    expect(parseCommitLink(`/files/a.md?rev=${hash}`)).toBeNull();
    expect(parseFileLink(`/files?path=a.md&rev=${hash}`)).toMatchObject({ path: 'a.md', rev: hash });
  });

  it('не трогает ссылки без ревизии, внешние и чужие пути', () => {
    expect(parseCommitLink('/files')).toBeNull();
    expect(parseCommitLink('/files?project=kb')).toBeNull();
    expect(parseCommitLink(`https://example.com/files?rev=${hash}`)).toBeNull();
    expect(parseCommitLink(`/filesystem?rev=${hash}`)).toBeNull();
    expect(parseCommitLink('/?doc=70')).toBeNull();
    expect(parseCommitLink('')).toBeNull();
  });
});
