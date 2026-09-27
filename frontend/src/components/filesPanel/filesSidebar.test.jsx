import buildFileTabs from './filesSidebar';

const t = (key) => key;
const tabsFor = (file) =>
  buildFileTabs({
    t,
    content: { type: 'file', path: 'x', file },
    contentLoading: false,
    path: 'x',
    project: 'kb',
    rev: '',
    snapshot: false,
    snapshotCommit: {},
    jump: null,
    onJump: () => {},
  }).map((tab) => [tab.key, tab.label]);

describe('buildFileTabs', () => {
  test('у кода — «Структура», у markdown — она же под именем «Разделы»', () => {
    expect(tabsFor({ language: 'java', binary: false })).toEqual([
      ['info', 'tabs.info'],
      ['outline', 'tabs.outline'],
    ]);
    expect(tabsFor({ language: 'markdown', binary: false })).toContainEqual(['outline', 'tabs.sections']);
  });

  // Структуру строит бэкенд, и не для всех языков: у прочих запрос ответил бы 400.
  test('у файла, для которого структуры нет, вкладки нет', () => {
    expect(tabsFor({ language: 'yaml', binary: false })).toEqual([['info', 'tabs.info']]);
    expect(tabsFor({ language: null, binary: false })).toEqual([['info', 'tabs.info']]);
    expect(tabsFor({ language: 'java', binary: true })).toEqual([['info', 'tabs.info']]);
  });
});
