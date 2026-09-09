import { createNavStore } from './navStore';
import { readPanelState, savePanelState } from './panelState';

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
