import { renderHook, act } from '@testing-library/react';
import useAppNavigation from './useAppNavigation';
import { STORAGE_KEY_PANELS } from './constants/storage';

/** Текущий адрес в том же виде, в каком его строит хук. */
const url = () => window.location.pathname + window.location.search;

/** Переставить jsdom на нужный адрес до монтирования хука. */
const go = (href) => window.history.replaceState({}, '', href);

beforeEach(() => {
  localStorage.clear();
  go('/chat');
});

describe('чтение адреса', () => {
  it('разбирает ресурс из пути', () => {
    go('/knowledge/doc/42?tab=content');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'knowledge', docId: '42', docTab: 'content' });
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
    act(() => result.current.openDoc('7')); // tab=summary — дефолт
    expect(url()).toBe('/knowledge/doc/7');
    act(() => result.current.setDocTab('content'));
    expect(url()).toBe('/knowledge/doc/7?tab=content');
    act(() => result.current.setSearch('фраза', 'hybrid')); // mode=hybrid — дефолт
    expect(url()).toBe('/knowledge/search?q=%D1%84%D1%80%D0%B0%D0%B7%D0%B0');
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
    expect(result.current.nav).toMatchObject({ view: 'knowledge', docId: '5', docTab: 'content' });
    expect(url()).toBe('/knowledge/doc/5?tab=content');
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

  it('находясь в чате, фоновый выбор всё же попадает в адрес', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('auto-picked', { navigate: false }));
    expect(url()).toBe('/chat/auto-picked');
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
