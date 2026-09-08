import { renderHook, act } from '@testing-library/react';
import useAppNavigation from './useAppNavigation';
import { STORAGE_KEY_PANELS } from '@/constants/storage';

/** Текущий адрес в том же виде, в каком его строит хук. */
const url = () => window.location.pathname + window.location.search;

/** Переставить DOM-окружение на нужный адрес до монтирования хука. */
const go = (href) => window.history.replaceState({}, '', href);

/** «Назад»/«Вперёд»: адрес меняет браузер, а хук узнаёт об этом из popstate. */
const back = (href) => {
  window.history.replaceState({}, '', href);
  window.dispatchEvent(new PopStateEvent('popstate'));
};

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

describe('ревизия в «Файлах»', () => {
  it('переезжает вместе с путём внутри одного репозитория', () => {
    go('/files/a/b.md?rev=v1');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('a/c.md'));
    expect(url()).toBe('/files/a/c.md?rev=v1');
  });

  it('не переезжает в другой репозиторий: имя ветки там значит не то же самое', () => {
    go('/files/a/b.md?rev=v1');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('', 'other'));
    expect(url()).toBe('/files?project=other');
  });

  it('уходит при переходе в режим изменений: у снимка их не бывает', () => {
    go('/files/a/b.md?rev=v1');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('a/b.md', undefined, { changes: true }));
    expect(url()).toBe('/files/a/b.md?changes=1');
  });
});

describe('подсветка в открытом файле', () => {
  it('читает запрос и пометку «это выражение» из адреса', () => {
    go('/files/a/b.md?find=needle&re=1');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ filePath: 'a/b.md', fileFind: 'needle', fileFindRegex: true });
  });

  it('переход из поиска приносит запрос в адрес файла вместе с ревизией', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('a/b.md', undefined, { rev: 'v1', find: 'needle' }));
    expect(url()).toBe('/files/a/b.md?rev=v1&find=needle');
  });

  // Иначе прежний запрос красил бы в новом файле случайные слова.
  it('не переезжает на файл, открытый не из поиска', () => {
    go('/files/a/b.md?find=needle');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openFilePath('a/c.md'));
    expect(url()).toBe('/files/a/c.md');
  });

  // Набранное в баре — не переход: возврат «Назад» из файла обязан вести в
  // выдачу, а не отматывать поиск по буквам.
  it('запрос из самого бара заменяет запись истории, а не добавляет', () => {
    go('/files/a/b.md');
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;
    act(() => result.current.setFileFind('needle', false));
    expect(url()).toBe('/files/a/b.md?find=needle');
    expect(window.history.length).toBe(before);
  });

  it('пометка «выражение» без запроса в адрес не пишется', () => {
    go('/files/a/b.md');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.setFileFind('', true));
    expect(url()).toBe('/files/a/b.md');
  });
});

describe('подсветка в открытом чате', () => {
  it('читает запрос из адреса чата', () => {
    go('/chat/c1?find=needle');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({ view: 'chat', chatId: 'c1', chatFind: 'needle' });
  });

  it('переход из поиска приносит запрос в адрес чата', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('c1', { find: 'needle' }));
    expect(url()).toBe('/chat/c1?find=needle');
  });

  // Иначе прежний запрос открывал бы бар в чате, где искать нечего.
  it('не переезжает на чат, открытый не из поиска', () => {
    go('/chat/c1?find=needle');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('c2'));
    expect(url()).toBe('/chat/c2');
  });

  it('запрос из самого бара заменяет запись истории, а не добавляет', () => {
    go('/chat/c1');
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;
    act(() => result.current.setChatFind('needle'));
    expect(url()).toBe('/chat/c1?find=needle');
    expect(window.history.length).toBe(before);
  });

  // «Назад» на запись с запросом обязан вернуть и подсветку: иначе бар закроется,
  // а канонизирующий replaceState следом сотрёт ?find= и из адреса.
  it('«Назад» возвращает запрос вместе с адресом', () => {
    go('/chat/c1');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openChat('c1', { find: 'needle' }));
    expect(result.current.nav.chatFind).toBe('needle');

    act(() => back('/chat/c1'));
    expect(result.current.nav.chatFind).toBe('');

    act(() => back('/chat/c1?find=needle'));
    expect(result.current.nav.chatFind).toBe('needle');
  });
});

describe('подсветка в открытом документе', () => {
  it('читает запрос и раздел из адреса документа', () => {
    go('/knowledge/doc/5?find=needle&section=%D0%A3%D1%81%D1%82%D0%B0%D0%BD%D0%BE%D0%B2%D0%BA%D0%B0+%3E+Docker');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({
      view: 'knowledge',
      docId: '5',
      docFind: 'needle',
      docSection: 'Установка > Docker',
    });
  });

  // Раздел ведёт к активному совпадению; без запроса совпадений нет и вести некуда.
  it('раздел без запроса не читается и не пишется', () => {
    go('/knowledge/doc/5?section=FAQ');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.docSection).toBe('');
    act(() => result.current.openDoc(6, { section: 'FAQ' }));
    expect(url()).toBe('/knowledge/doc/6');
  });

  it('переход из поиска приносит запрос и раздел в адрес документа', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openDoc(5, { find: 'needle', section: 'FAQ > Вопрос[2]' }));
    expect(url()).toBe('/knowledge/doc/5?find=needle&section=FAQ+%3E+%D0%92%D0%BE%D0%BF%D1%80%D0%BE%D1%81%5B2%5D');
  });

  it('не переезжает на документ, открытый не из поиска', () => {
    go('/knowledge/doc/5?find=needle&section=FAQ');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openDoc(6));
    expect(url()).toBe('/knowledge/doc/6');
  });

  // Бар фиксирует запрос по Enter и уходу фокуса — тот же запрос не должен
  // уводить с совпадения в разделе, к которому пришли.
  it('фиксация того же запроса из бара раздел не трогает', () => {
    go('/knowledge/doc/5?find=needle&section=FAQ');
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.setDocFind('needle'));
    expect(url()).toBe('/knowledge/doc/5?find=needle&section=FAQ');
  });

  // Набранный в баре другой запрос уже не тот, к которому относился раздел.
  it('другой запрос из бара заменяет запись истории и снимает раздел', () => {
    go('/knowledge/doc/5?find=needle&section=FAQ');
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;
    act(() => result.current.setDocFind('other'));
    expect(url()).toBe('/knowledge/doc/5?find=other');
    expect(window.history.length).toBe(before);
  });
});

describe('единый поиск', () => {
  it('разбирает запрос, категорию и фильтры категории «файлы»', () => {
    go('/search?q=needle&in=files&path=backend%2F**&rev=v1&regex=1&untracked=1');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav).toMatchObject({
      view: 'search',
      searchQuery: 'needle',
      searchScope: 'files',
      searchPath: 'backend/**',
      searchRev: 'v1',
      searchRegex: true,
      searchUntracked: true,
    });
  });

  it('незнакомую категорию из адреса заменяет дефолтной, а не показывает пустоту', () => {
    go('/search?q=needle&in=bogus');
    const { result } = renderHook(() => useAppNavigation());
    expect(result.current.nav.searchScope).toBe('files');
  });

  it('пишет категорию всегда, а фильтры — только заданные', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openSearch('needle', 'chats'));
    expect(url()).toBe('/search?q=needle&in=chats');
  });

  it('поиск — переход, а уточнение фильтров — нет', () => {
    const { result } = renderHook(() => useAppNavigation());
    const before = window.history.length;

    act(() => result.current.openSearch('needle', 'files'));
    act(() => result.current.refineSearch({ searchUntracked: true }));
    act(() => result.current.refineSearch({ searchScope: 'docs' }));

    expect(url()).toBe('/search?q=needle&in=docs&untracked=1');
    // Один переход на весь подбор фильтров: «Назад» возвращает туда, откуда искали.
    expect(window.history.length).toBe(before + 1);
  });

  it('уход фокуса без правки фильтра ничего не меняет', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openSearch('needle', 'files'));
    const before = result.current.nav;

    act(() => result.current.refineSearch({ searchPath: '' }));

    // Тот же объект: перерисовывать все смонтированные разделы не из-за чего.
    expect(result.current.nav).toBe(before);
  });

  it('не тащит запрос единого поиска в адреса других разделов', () => {
    const { result } = renderHook(() => useAppNavigation());
    act(() => result.current.openSearch('needle', 'docs'));
    act(() => result.current.openDoc('7'));
    expect(url()).toBe('/knowledge/doc/7');
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
