import { renderHook, act } from '@testing-library/react';
import useAppNavigation from './useAppNavigation';
import { STORAGE_KEY_PANELS } from './constants/storage';

/** Текущий адрес в том же виде, в каком его строит хук. */
const url = () => window.location.pathname + window.location.search;

/** Переставить DOM-окружение на нужный адрес до монтирования хука. */
const go = (href) => window.history.replaceState({}, '', href);

beforeEach(() => {
  localStorage.clear();
  go('/chat');
});

describe('чтение адреса', () => {
  it('разбирает ресурс из пути', () => {
    go('/knowledge/doc/42');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'knowledge', docId: '42' });
  });

  it('разбирает поиск', () => {
    go('/knowledge/search?q=%D1%82%D0%B5%D1%81%D1%82&mode=semantic');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'knowledge', search: 'тест', mode: 'semantic', docId: null });
  });

  it('разбирает путь файла целиком, включая вложенность и пробелы', () => {
    go('/files/backend/src/main/My%20File.java');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'files', filePath: 'backend/src/main/My File.java' });
  });

  it('разбирает чат из пути', () => {
    go('/chat/abc-123');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'chat', chatId: 'abc-123' });
  });
});

describe('построение адреса', () => {
  it('переносит ресурс в путь, а не в query', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openDoc('7'));
    expect(url()).toBe('/knowledge/doc/7');
    act(() => result.current.openFilePath('a/b.md'));
    expect(url()).toBe('/files/a/b.md');
    act(() => result.current.openChat('c1'));
    expect(url()).toBe('/chat/c1');
  });

  it('не пишет дефолтные значения в query', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openDoc('7'));
    expect(url()).toBe('/knowledge/doc/7');
    act(() => result.current.setSearch('фраза', 'hybrid')); // mode=hybrid — дефолт
    expect(url()).toBe('/knowledge/search?q=%D1%84%D1%80%D0%B0%D0%B7%D0%B0');
    act(() => result.current.setSearch('фраза', 'semantic'));
    expect(url()).toBe('/knowledge/search?q=%D1%84%D1%80%D0%B0%D0%B7%D0%B0&mode=semantic');
  });

  it('не тащит активный чат в адреса других разделов', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('c1'));
    act(() => result.current.switchView('knowledge'));
    expect(url()).not.toContain('chat=');
    expect(url()).toBe('/knowledge');
  });
});

describe('обратная совместимость со старой схемой', () => {
  it('открывает старую ссылку на документ и канонизирует адрес', () => {
    go('/knowledge?doc=5&tab=content&chat=old-chat');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'knowledge', docId: '5' });
    // tab=content означал «показать содержимое» — теперь оно и так в центре.
    expect(url()).toBe('/knowledge/doc/5');
  });

  it('переносит вкладки старого ?tab= в правую панель', () => {
    // summary/contents/attachments были вкладками центра, а теперь живут справа.
    go('/knowledge?doc=5&tab=attachments');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.rightTab).toBe('attachments');
    expect(url()).toBe('/knowledge/doc/5?right=attachments');
  });

  it('канонизирует адрес НА МЕСТЕ, не добавляя запись в историю', () => {
    // Канонизация — это не переход: с pushState «Назад» возвращал бы на
    // legacy-адрес, который тут же канонизируется снова, и кнопка выглядела бы
    // сломанной.
    go('/knowledge?doc=5&tab=content');
    const before = window.history.length;
    renderHook(() => useAppNavigation());
    expect(url()).toBe('/knowledge/doc/5');
    expect(window.history.length).toBe(before);
  });

  it('открывает doc-ссылку из markdown в корне пути (`/?doc=N`)', () => {
    // Именно эта форма лежит в описаниях документов и сообщениях чата, поэтому
    // «открыть в новой вкладке» приземляется на неё — раздел из адреса неявный.
    go('/?doc=70');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'knowledge', docId: '70' });
    expect(url()).toBe('/knowledge/doc/70');
  });

  it('открывает старую ссылку на файл', () => {
    go('/files?path=backend/pom.xml');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.filePath).toBe('backend/pom.xml');
    expect(url()).toBe('/files/backend/pom.xml');
  });

  it('понимает legacy ?view= и помнит чат из него', () => {
    go('/?view=settings&chat=old-chat');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.view).toBe('settings');
    expect(url()).toBe('/settings');
    // Чат из старой ссылки не потерян — возвращаемся в раздел «Чат».
    act(() => result.current.switchView('chat'));
    expect(url()).toBe('/chat/old-chat');
  });
});

describe('фоновый выбор чата', () => {
  it('не уводит с открытого раздела и не трогает адрес', () => {
    // Панель чата смонтирована всегда и при загрузке сама выбирает чат. Без
    // navigate:false она утаскивала бы deep link на файл в /chat.
    go('/files/backend/build.gradle');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('auto-picked', { navigate: false }));
    expect(result.current.nav.view).toBe('files');
    expect(url()).toBe('/files/backend/build.gradle');
    // Но выбор запомнен — возврат в чат открывает именно его.
    act(() => result.current.switchView('chat'));
    expect(url()).toBe('/chat/auto-picked');
  });

  it('находясь в чате, фоновый выбор всё же попадает в адрес, но не как переход', () => {
    // Автовыбор при свежей загрузке /chat не должен плодить запись истории —
    // иначе «Назад» приводил бы на визуально неотличимый /chat (ChatWindow уже
    // держит выбор в своём стейте) и выглядел бы нерабочим.
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;
    act(() => result.current.openChat('auto-picked', { navigate: false }));
    expect(url()).toBe('/chat/auto-picked');
    expect(window.history.length).toBe(before);
  });
});

describe('память последнего открытого', () => {
  it('возвращает в раздел последний ресурс', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('a/b.md'));
    act(() => result.current.openDoc('9'));
    act(() => result.current.switchView('files'));
    expect(url()).toBe('/files/a/b.md');
    act(() => result.current.switchView('knowledge'));
    expect(url()).toBe('/knowledge/doc/9');
  });
});

describe('раскладка панелей', () => {
  it('пишет в адрес только не-дефолтное состояние', () => {
    const { result } = renderHook(() => useAppNavigation());
    expect(url()).toBe('/chat');
    act(() => result.current.toggleLeftPanel());
    expect(url()).toBe('/chat?left=0');
    act(() => result.current.setRightTab('attachments'));
    expect(url()).toBe('/chat?left=0&right=attachments');
    act(() => result.current.toggleLeftPanel());
    expect(url()).toBe('/chat?right=attachments');
  });

  it('повторное раскрытие той же вкладки не сворачивает панель', () => {
    // Панель раскрывают и действия (загрузили вложение → показать вложения),
    // поэтому сеттер не переключающий: свернуть можно только явным null.
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.setRightTab('attachments'));
    act(() => result.current.setRightTab('attachments'));
    expect(result.current.nav.rightTab).toBe('attachments');
    act(() => result.current.setRightTab(null));
    expect(result.current.nav.rightTab).toBeNull();
    expect(url()).toBe('/chat');
  });

  it('сворачивание панели не копит записи истории', () => {
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;
    act(() => result.current.toggleLeftPanel());
    act(() => result.current.setRightTab('attachments'));
    expect(window.history.length).toBe(before);
  });

  it('холостое раскрытие панели не съедает следующую запись истории', () => {
    // Режим записи ставит инициатор изменения. Если бы его сбрасывал эффект,
    // «раскрыть уже раскрытую вкладку» (так делает загрузка вложения при
    // открытой панели) не вызвало бы ре-рендер, и 'replace' протёк бы в
    // следующий переход — тот записался бы поверх текущей записи.
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.setRightTab('attachments'));
    act(() => result.current.setRightTab('attachments')); // холостой вызов
    const before = window.history.length;
    act(() => result.current.openChat('c1'));
    expect(window.history.length).toBe(before + 1);
    expect(url()).toBe('/chat/c1?right=attachments');
  });

  it('раскладка запоминается отдельно для каждого раздела', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.toggleLeftPanel()); // чат: левая свёрнута
    act(() => result.current.switchView('knowledge'));
    expect(result.current.nav.leftCollapsed).toBe(false); // у KB своя раскладка
    expect(url()).toBe('/knowledge');
    act(() => result.current.switchView('chat'));
    expect(result.current.nav.leftCollapsed).toBe(true);
    expect(url()).toBe('/chat?left=0');
  });

  it('явная раскладка из адреса важнее запомненной', () => {
    localStorage.setItem(STORAGE_KEY_PANELS, JSON.stringify({ files: { leftCollapsed: true, rightTab: null } }));
    go('/files?left=1');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.leftCollapsed).toBe(false);
  });
});
