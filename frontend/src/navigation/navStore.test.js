import { createNavStore } from './navStore';
import { readPanelState, savePanelState } from './panelState';
import { commitUrl } from './urlScheme';

/** Текущий адрес в том же виде, в каком его строит стор. */
const url = () => window.location.pathname + window.location.search;

/** Переставить DOM-окружение на нужный адрес до создания стора. */
const go = (href) => window.history.replaceState({}, '', href);

/** Стор без React: переходы через api, состояние — из снимка. */
const mount = (options) => {
  const store = createNavStore(options);
  store.canonicalize();
  window.addEventListener('popstate', store.onPopState);
  return { ...store.api, nav: () => store.getSnapshot().nav, pendingView: () => store.getSnapshot().pendingView };
};

/** «Назад»/«Вперёд»: адрес меняет браузер, а стор узнаёт об этом из popstate. */
const back = (href) => {
  window.history.replaceState({}, '', href);
  window.dispatchEvent(new PopStateEvent('popstate'));
};

beforeEach(() => {
  localStorage.clear();
  go('/chat');
});

describe('уход с несохранёнными правками', () => {
  // Вопрос задаёт навигация перед каждым переходом в другой раздел, а не тот,
  // кто вспомнил про гарду: ссылка на файл защищена так же, как вкладка.
  it('без запрета переключает раздел сразу', () => {
    const s = mount({ canLeave: () => true });
    s.switchView('files');
    expect(url()).toBe('/files');
    expect(s.pendingView()).toBeNull();
  });

  it('с запретом откладывает переход и проигрывает его после подтверждения', () => {
    go('/knowledge/doc/5');
    const s = mount({ canLeave: (prev) => prev.view !== 'knowledge' });
    const before = window.history.length;
    s.switchView('chat');
    expect(url()).toBe('/knowledge/doc/5');
    expect(s.nav().view).toBe('knowledge');
    expect(s.pendingView()).toBe('chat');

    s.confirmLeave();
    expect(url()).toBe('/chat');
    expect(s.pendingView()).toBeNull();
    expect(window.history.length).toBe(before + 1);
  });

  it('отказ оставляет на месте', () => {
    go('/knowledge/doc/5');
    const s = mount({ canLeave: () => false });
    s.switchView('chat');
    s.cancelLeave();
    expect(url()).toBe('/knowledge/doc/5');
    expect(s.pendingView()).toBeNull();
  });

  // Ссылка на файл открывает «Файлы» сразу на пути — один переход. С правками
  // он обязан ждать ответа целиком: иначе вопрос задан, а в файл ушли всё равно.
  it('переход к файлу откладывается целиком и остаётся одной записью', () => {
    go('/knowledge/doc/5');
    const s = mount({ canLeave: (prev) => prev.view !== 'knowledge' });
    const before = window.history.length;
    s.openFilePath('a/b.md', '', { changes: true });
    expect(url()).toBe('/knowledge/doc/5');
    expect(s.pendingView()).toBe('files');

    s.confirmLeave();
    expect(url()).toBe('/files/a/b.md?changes=1');
    expect(window.history.length).toBe(before + 1);
  });

  it('отложенный переход не трогает память: отказ возвращает в прежний чат', () => {
    go('/chat/c1');
    let dirty = false;
    const s = mount({ canLeave: () => !dirty });
    s.switchView('knowledge');
    dirty = true;
    s.openChat('c2');
    expect(s.pendingView()).toBe('chat');
    s.cancelLeave();
    dirty = false;
    s.switchView('chat');
    expect(url()).toBe('/chat/c1');
  });

  it('спрашивает только про смену раздела: раскладка и бар идут мимо вопроса', () => {
    go('/knowledge/doc/5');
    const s = mount({ canLeave: () => false });
    s.setRightTab('attachments');
    s.setDocFind('needle');
    expect(url()).toBe('/knowledge/doc/5?find=needle&right=attachments');
    expect(s.pendingView()).toBeNull();
  });

  it('«Назад» браузера снимает вопрос: запись, с которой уходили, уже позади', () => {
    go('/knowledge/doc/5');
    const s = mount({ canLeave: () => false });
    s.switchView('chat');
    expect(s.pendingView()).toBe('chat');
    back('/chat/c1');
    expect(s.pendingView()).toBeNull();
    expect(s.nav()).toMatchObject({ view: 'chat', chatId: 'c1' });
  });

  it('уход другим путём снимает вопрос: он был про раздел, который уже покинули', () => {
    // Поиск из базы знаний не спрашивает; ушли в него — вопрос про «Файлы»
    // не должен ни висеть над результатами, ни сработать потом из поиска.
    go('/knowledge/doc/5');
    const s = mount({ canLeave: (prev, next) => prev.view !== 'knowledge' || next.view === 'search' });
    s.switchView('files');
    expect(s.pendingView()).toBe('files');
    s.openSearch('needle', 'docs');
    expect(url()).toBe('/search?q=needle&in=docs');
    expect(s.pendingView()).toBeNull();
    s.confirmLeave();
    expect(url()).toBe('/search?q=needle&in=docs');
  });

  it('подтверждение проигрывает переход от текущего состояния', () => {
    // Пока вопрос открыт, состояние могло сдвинуться (в базе знаний открыли
    // другой документ) — переход берёт его, а не снимок на момент вопроса:
    // возврат в базу знаний ведёт к документу, открытому последним.
    go('/knowledge/doc/5');
    let dirty = true;
    const s = mount({ canLeave: () => !dirty });
    s.switchView('files');
    s.openDoc('7');
    expect(url()).toBe('/knowledge/doc/7');
    s.confirmLeave();
    expect(url()).toBe('/files');
    dirty = false;
    s.switchView('knowledge');
    expect(url()).toBe('/knowledge/doc/7');
  });
});

describe('фоновый выбор чата из другого раздела', () => {
  it('черновик, ставший чатом, пока открыты «Файлы», возвращает в настоящий чат', () => {
    // Загрузка вложения в черновик завершается, когда человек уже в «Файлах»:
    // адрес там про чат молчит, но возврат в чат берёт id из состояния — и
    // это должен быть новый uuid, а не /chat/new с пустым черновиком.
    go('/chat/new');
    const s = mount();
    s.switchView('files');
    s.openChat('uuid-1', { navigate: false });
    expect(url()).toBe('/files');
    s.switchView('chat');
    expect(url()).toBe('/chat/uuid-1');
  });
});

describe('область поиска по файлам', () => {
  it('поиск из «Файлов» ищет в открытой там ревизии и репозитории', () => {
    go('/files/a/b.md?project=other&rev=release-1');
    const s = mount();
    s.openSearch('needle', 'files');
    expect(url()).toBe('/search?q=needle&in=files&project=other&rev=release-1');
  });

  it('поиск из рабочего дерева не тащит ревизию прошлого поиска', () => {
    // Из снимка искали, вышли в рабочее дерево, ищут снова: «Файлы» показывают
    // рабочее дерево, и выдача обязана быть про него же.
    go('/files?rev=release-1');
    const s = mount();
    s.openSearch('needle', 'files');
    s.switchView('files');
    s.setFileRev('');
    s.openSearch('needle', 'files');
    expect(url()).toBe('/search?q=needle&in=files');
  });

  it('поиск в другом репозитории не тащит маску пути от прежнего', () => {
    // `backend/**` написали про прежний репозиторий; в новом такого каталога
    // может не быть вовсе — это ноль результатов без объяснений.
    go('/search?q=x&in=files&path=backend/**');
    const s = mount();
    s.openFilePath('', 'other');
    s.openSearch('needle', 'files');
    expect(url()).toBe('/search?q=needle&in=files&project=other');
  });

  it('ревизия не переезжает в репозиторий, восстановленный переключением раздела', () => {
    // Вкладка «Файлы» возвращает последний открытый путь — он из другого
    // репозитория, и ревизия прежнего назвала бы в нём чужой коммит или ничего.
    go('/files/a/b.md');
    const s = mount();
    s.openFilePath('', 'other', { rev: 'release-1' });
    s.switchView('chat');
    s.switchView('files');
    expect(url()).toBe('/files/a/b.md');
    s.openSearch('needle', 'files');
    expect(url()).toBe('/search?q=needle&in=files');
  });

  it('повторный поиск из самого поиска оставляет выбранные фильтры', () => {
    // Ревизию и репозиторий здесь выбрали руками в левой панели — запрос,
    // отправленный поверх них, уточняет тот же поиск, а не начинает новый.
    go('/search?q=x&in=files&project=other&rev=release-1');
    const s = mount();
    s.openSearch('needle', 'files');
    expect(url()).toBe('/search?q=needle&in=files&project=other&rev=release-1');
  });
});

describe('раскладка панелей при смене раздела', () => {
  it('переход по ссылке на файл приносит раскладку «Файлов», а не раздела-источника', () => {
    // Раньше файл открывался с панелями чата — и они же записывались как
    // раскладка «Файлов», затирая ту, что человек там настроил.
    savePanelState('files', { leftCollapsed: true, rightTab: null });
    go('/chat/c1?right=attachments');
    const s = mount();
    s.openFilePath('a/b.md');
    expect(url()).toBe('/files/a/b.md?left=0');
    expect(readPanelState('files')).toEqual({ leftCollapsed: true, rightTab: null });
  });

  it('документ из поиска открывается с раскладкой базы знаний', () => {
    savePanelState('knowledge', { leftCollapsed: false, rightTab: 'attachments' });
    go('/search?q=x&in=docs&left=0');
    const s = mount();
    s.openDoc('5');
    expect(url()).toBe('/knowledge/doc/5?right=attachments');
  });

  it('раскладка из ссылки запоминается с первого открытия', () => {
    // Ссылка принесла раскрытую панель; уход в другой раздел и возврат обязаны
    // её вернуть, а не подставить запомненную ранее (или дефолт).
    go('/knowledge/doc/5?right=attachments');
    const s = mount();
    s.switchView('chat');
    s.switchView('knowledge');
    expect(url()).toBe('/knowledge/doc/5?right=attachments');
  });
});

describe('«Назад»/«Вперёд» с несохранёнными правками', () => {
  // Браузер уже сменил адрес: стор возвращает его через history.go и спрашивает.
  // go подменён — popstate, который он вызвал бы, тесты проигрывают сами.
  let go;
  beforeEach(() => {
    go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
  });
  afterEach(() => go.mockRestore());

  /** popstate на запись `entry` — так, как его прислал бы браузер. */
  const popTo = (entry) => {
    window.history.replaceState(entry.state, '', entry.href);
    window.dispatchEvent(new PopStateEvent('popstate', { state: entry.state }));
  };
  const here = () => ({ state: window.history.state, href: url() });

  const twoDocs = (options) => {
    window.history.replaceState(null, '', '/knowledge/doc/5');
    const s = mount(options);
    const first = here();
    s.openDoc('7');
    return { s, first, second: here() };
  };

  it('без правок меняет документ сразу', () => {
    const { s, first } = twoDocs({ canReplaceDoc: () => true });
    popTo(first);
    expect(s.nav().docId).toBe('5');
    expect(go).not.toHaveBeenCalled();
  });

  it('с правками возвращает адрес, спрашивает и повторяет переход после подтверждения', () => {
    const { s, first, second } = twoDocs({ canReplaceDoc: () => false });
    popTo(first);
    expect(go).toHaveBeenLastCalledWith(1);
    expect(s.nav().docId).toBe('7');
    expect(s.pendingView()).toBe('knowledge');

    popTo(second); // браузер вернулся по go(1)
    expect(s.nav().docId).toBe('7');

    s.confirmLeave();
    expect(go).toHaveBeenLastCalledWith(-1);
    expect(s.pendingView()).toBeNull();
    popTo(first); // повтор — уже без вопроса
    expect(s.nav().docId).toBe('5');
    expect(go).toHaveBeenCalledTimes(2);
  });

  it('«остаться» оставляет документ и вопрос снимает', () => {
    const { s, first, second } = twoDocs({ canReplaceDoc: () => false });
    popTo(first);
    popTo(second);
    s.cancelLeave();
    expect(s.pendingView()).toBeNull();
    expect(s.nav().docId).toBe('7');
    expect(url()).toBe('/knowledge/doc/7');
  });

  it('уход в другой раздел не спрашивает — база знаний правки не теряет', () => {
    window.history.replaceState(null, '', '/chat');
    const s = mount({ canReplaceDoc: () => false });
    const chat = here();
    s.openDoc('7');
    popTo(chat);
    expect(s.nav().view).toBe('chat');
    expect(go).not.toHaveBeenCalled();
  });

  it('к записи без метки стора переходит без вопроса', () => {
    const { s } = twoDocs({ canReplaceDoc: () => false });
    back('/knowledge/doc/5');
    expect(s.nav().docId).toBe('5');
    expect(go).not.toHaveBeenCalled();
  });
});

describe('удалённый документ', () => {
  it('адрес сбрасывается на месте, и вкладка базы знаний на него больше не ведёт', () => {
    go('/knowledge/doc/5');
    const s = mount();
    const before = window.history.length;
    s.forgetDoc(5);
    expect(url()).toBe('/knowledge');
    expect(window.history.length).toBe(before);

    s.switchView('chat');
    s.switchView('knowledge');
    expect(s.nav().docId).toBeNull();
  });

  it('чужой документ не трогает ни адрес, ни память', () => {
    go('/knowledge/doc/5');
    const s = mount();
    s.forgetDoc(7);
    expect(url()).toBe('/knowledge/doc/5');
    s.switchView('chat');
    s.switchView('knowledge');
    expect(s.nav().docId).toBe('5');
  });
});

describe('переход к коммиту', () => {
  const hash = '0123456789abcdef0123456789abcdef01234567';

  it('одна запись истории: снимок, изменения слева, вкладка «Коммит» — тот же адрес, что у ссылки', () => {
    go('/chat/7');
    const s = mount();
    s.openFilePath('', 'kb', { rev: hash, changes: true, right: 'commit' });
    expect(url()).toBe(commitUrl(hash, 'kb'));
    expect(s.nav()).toMatchObject({ view: 'files', fileRev: hash, fileChanges: true, rightTab: 'commit' });
    back('/chat/7');
    expect(s.nav().view).toBe('chat');
  });

  it('без right раскладка раздела остаётся той, что была', () => {
    savePanelState('files', { leftCollapsed: false, rightTab: 'info' });
    const s = mount();
    s.openFilePath('a.md', undefined, { rev: hash });
    expect(s.nav().rightTab).toBe('info');
  });

  it('адрес коммита читается обратно без изменений', () => {
    go(commitUrl(hash));
    const s = mount();
    expect(url()).toBe(commitUrl(hash));
    expect(s.nav()).toMatchObject({ view: 'files', filePath: '', fileRev: hash, rightTab: 'commit' });
  });
});

describe('возврат из снимка, открытого по ячейке blame', () => {
  const hash = '0123456789abcdef0123456789abcdef01234567';
  afterEach(() => vi.restoreAllMocks());

  // Прокрутку внутреннего блока браузер на «Назад» не вернёт: место, с которого
  // ушли, остаётся в адресе той записи — выделенными строками ханка.
  it('строки ханка пишутся в запись, с которой ушли, а переход — одна новая запись', () => {
    go('/files/a.js?blame=1');
    const s = mount();
    const replaced = vi.spyOn(window.history, 'replaceState');
    const pushed = vi.spyOn(window.history, 'pushState');
    const before = window.history.length;

    s.openFilePath('old.js', undefined, { rev: hash, lines: '7-8', backLines: '3-4', right: 'commit' });

    expect(replaced.mock.calls.map((c) => c[2])).toEqual(['/files/a.js?blame=1&lines=3-4']);
    expect(pushed.mock.calls.map((c) => c[2])).toEqual([`/files/old.js?rev=${hash}&blame=1&lines=7-8&right=commit`]);
    expect(replaced.mock.invocationCallOrder[0]).toBeLessThan(pushed.mock.invocationCallOrder[0]);
    expect(window.history.length).toBe(before + 1);

    back('/files/a.js?blame=1&lines=3-4');
    expect(s.nav()).toMatchObject({ filePath: 'a.js', fileRev: '', fileLines: '3-4' });
  });

  // Метка — про место в «Файлах»; из другого раздела возвращаться не к чему.
  it('вне «Файлов» текущую запись не трогает', () => {
    go('/chat/7');
    const s = mount();
    const replaced = vi.spyOn(window.history, 'replaceState');

    s.openFilePath('old.js', undefined, { rev: hash, backLines: '3-4' });

    expect(replaced).not.toHaveBeenCalled();
  });
});
