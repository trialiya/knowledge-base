import { renderHook, waitFor, act } from '@testing-library/react';
import useInChatSearch, { resolveActiveMatchMid } from './useInChatSearch';
import chatApi from '@/api/chatApi';

vi.mock('@/api/chatApi');

// Пузыри как в стейте чата: загруженные из БД имеют dbId, свежие (отправка/стриминг
// текущей сессии) — нет.
const loaded = (mid, dbId, text) => ({ mid, dbId, text, sender: 'user' });
const fresh = (mid, text) => ({ mid, dbId: null, text, sender: 'user' });

describe('resolveActiveMatchMid', () => {
  it('загруженное сообщение: находит пузырь по dbId', () => {
    const messages = [loaded('m1', 10, 'про жирафов'), loaded('m2', 11, 'про слонов')];
    const matches = [{ id: 10 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 10 }, query: 'жираф' })).toBe('m1');
  });

  it('свежие сообщения: k-й свежий хит соответствует k-му свежему пузырю с запросом', () => {
    const messages = [
      loaded('m1', 10, 'старое про жирафов'),
      fresh('m2', 'спросил про жирафов'), // персистнут на бэке как id=20
      fresh('m3', 'ответ без совпадения'),
      fresh('m4', 'снова жирафы'), // персистнут как id=21
    ];
    const matches = [{ id: 10 }, { id: 20 }, { id: 21 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 20 }, query: 'жираф' })).toBe('m2');
    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 21 }, query: 'жираф' })).toBe('m4');
  });

  it('чат без загруженных страниц (создан в этой сессии): маппинг только по порядку', () => {
    const messages = [fresh('m1', 'жирафы раз'), fresh('m2', 'мимо'), fresh('m3', 'жирафы два')];
    const matches = [{ id: 5 }, { id: 7 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 5 }, query: 'жираф' })).toBe('m1');
    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 7 }, query: 'жираф' })).toBe('m3');
  });

  it('совпадение в незагруженной старой странице — null (догрузку ведёт эффект)', () => {
    const messages = [loaded('m1', 10, 'хвост истории')];
    const matches = [{ id: 3 }, { id: 10 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 3 }, query: 'хвост' })).toBeNull();
  });

  it('свежий хит без подходящего пузыря — null, а не чужой пузырь', () => {
    // Бэкенд уже видит сообщение, а стейт ещё нет (событие не дошло).
    const messages = [loaded('m1', 10, 'старое')];
    const matches = [{ id: 20 }];

    expect(resolveActiveMatchMid({ messages, matches, activeMatch: { id: 20 }, query: 'жираф' })).toBeNull();
  });

  it('нет активного совпадения или сообщений — null', () => {
    expect(resolveActiveMatchMid({ messages: [], matches: [], activeMatch: null, query: 'q' })).toBeNull();
    expect(resolveActiveMatchMid({ messages: undefined, matches: [], activeMatch: { id: 1 }, query: 'q' })).toBeNull();
  });
});

describe('useInChatSearch — догрузка старых страниц не запускается лишний раз', () => {
  afterEach(() => vi.resetAllMocks());

  // Регрессия: дефолтный (самый свежий) хит уже загружен и виден в свежем `messages`,
  // но список из getChats отстаёт на рендер (его зеркало обновляет эффект в
  // useChatList). Первичная проверка догрузки, смотрящая только туда, по ошибке
  // стартует лишнюю loadOlderMessages — та вставляет старые сообщения мимо
  // scroll-preserving логики MessageList, из-за чего уже подсвеченное сообщение
  // мгновенно уезжало из вьюпорта.
  it('не вызывает loadOlderMessages, если совпадение уже есть в свежих messages', async () => {
    const messages = [loaded('m1', 10, 'про жирафов')];
    // Список из getChats «отстал» — там ещё нет сообщений этого чата.
    const getChats = () => [{ id: 'chat-1', messages: [], hasMore: true }];
    const loadOlderMessages = vi.fn().mockResolvedValue(true);
    chatApi.searchMessages.mockResolvedValue([{ id: 10, createdAt: '2026-01-01' }]);

    // Запрос приходит из адреса — так открывает чат карточка результата.
    const { result } = renderHook(() =>
      useInChatSearch({ activeChatId: 'chat-1', getChats, loadOlderMessages, messages, find: 'жираф' }),
    );

    await waitFor(() => expect(result.current.activeMatchMid).toBe('m1'));
    expect(loadOlderMessages).not.toHaveBeenCalled();
  });

  // Регрессия: лента чата может стать undefined (список чатов перестроился, страница
  // ещё не загружена), пока активное совпадение живо. Первичная проверка обязана
  // обращаться к ней через опциональную цепочку — прямой messages.some(...) падал с
  // TypeError, а догрузка старых страниц при этом должна идти своим чередом.
  it('не падает, если messages стал undefined при живом совпадении', async () => {
    const messages = [loaded('m1', 10, 'про жирафов')];
    let chatList = [{ id: 'chat-1', messages, hasMore: true }];
    const getChats = () => chatList;
    const loadOlderMessages = vi.fn().mockResolvedValue(false);
    // Активным по умолчанию становится последнее — то, что уже в ленте.
    chatApi.searchMessages.mockResolvedValue([
      { id: 3, createdAt: '2026-01-01' },
      { id: 10, createdAt: '2026-01-02' },
    ]);

    const { result, rerender } = renderHook((props) => useInChatSearch(props), {
      initialProps: { activeChatId: 'chat-1', getChats, loadOlderMessages, messages, find: 'жираф' },
    });

    await waitFor(() => expect(result.current.activeMatchMid).toBe('m1'));

    chatList = [{ id: 'chat-1', messages: undefined, hasMore: true }];
    rerender({ activeChatId: 'chat-1', getChats, loadOlderMessages, messages: undefined, find: 'жираф' });
    // Переход к совпадению вне ленты перезапускает первичную проверку по messages.
    act(() => result.current.goNext());

    await waitFor(() => expect(loadOlderMessages).toHaveBeenCalledWith('chat-1'));
  });
});

/**
 * Запрос find-бара живёт в адресе — как и у открытого файла: так его переживают
 * Ctrl+клик по карточке результата, перезагрузка и ссылка, которой поделились.
 */
describe('useInChatSearch — запрос и адрес', () => {
  afterEach(() => vi.resetAllMocks());

  const mount = (props) => {
    chatApi.searchMessages.mockResolvedValue([{ id: 10, createdAt: '2026-01-01' }]);
    return renderHook((p) => useInChatSearch(p), {
      initialProps: {
        activeChatId: 'chat-1',
        getChats: () => [{ id: 'chat-1', messages: [loaded('m1', 10, 'про жирафов')], hasMore: false }],
        loadOlderMessages: vi.fn(),
        messages: [loaded('m1', 10, 'про жирафов')],
        find: '',
        ...props,
      },
    });
  };

  it('запрос из адреса открывает бар сам', () => {
    const { result } = mount({ find: 'жираф' });
    expect(result.current.open).toBe(true);
    expect(result.current.query).toBe('жираф');
  });

  it('без запроса в адресе бар закрыт — чат открыли не из поиска', () => {
    expect(mount().result.current.open).toBe(false);
  });

  // «Назад» и переход по ссылке меняют адрес под уже открытым чатом.
  it('следует за адресом, а не только за первым рендером', () => {
    const { result, rerender } = mount();
    rerender({
      activeChatId: 'chat-1',
      getChats: () => [{ id: 'chat-1', messages: [], hasMore: false }],
      loadOlderMessages: vi.fn(),
      messages: [],
      find: 'слон',
    });
    expect(result.current.query).toBe('слон');
    expect(result.current.open).toBe(true);
  });

  it('набранное уходит в адрес по фиксации, а не по букве', () => {
    const onFindChange = vi.fn();
    const { result } = mount({ onFindChange });
    act(() => result.current.openBar());
    act(() => result.current.setQuery('жираф'));
    expect(onFindChange).not.toHaveBeenCalled();

    act(() => result.current.commitQuery());
    expect(onFindChange).toHaveBeenCalledWith('жираф');
  });

  it('закрытие бара стирает запрос из адреса', () => {
    const onFindChange = vi.fn();
    const { result } = mount({ find: 'жираф', onFindChange });
    act(() => result.current.close());
    expect(result.current.open).toBe(false);
    expect(onFindChange).toHaveBeenCalledWith('');
  });
});

/**
 * Клик по строке карточки результата называет сообщение (`?msg=`): бар садится
 * на него, а не на самое свежее совпадение запроса.
 */
describe('useInChatSearch — сообщение из адреса', () => {
  afterEach(() => vi.resetAllMocks());

  const three = [loaded('m1', 10, 'жирафы раз'), loaded('m2', 20, 'жирафы два'), loaded('m3', 30, 'жирафы три')];
  const hits = [
    { id: 10, createdAt: '2026-01-01' },
    { id: 20, createdAt: '2026-01-02' },
    { id: 30, createdAt: '2026-01-03' },
  ];

  const mount = (props) => {
    chatApi.searchMessages.mockResolvedValue(hits);
    return renderHook((p) => useInChatSearch(p), {
      initialProps: {
        activeChatId: 'chat-1',
        getChats: () => [{ id: 'chat-1', messages: three, hasMore: false }],
        loadOlderMessages: vi.fn(),
        messages: three,
        find: 'жираф',
        msg: '',
        ...props,
      },
    });
  };

  it('активным становится названное сообщение, а не самое свежее', async () => {
    const { result } = mount({ msg: '20' });
    await waitFor(() => expect(result.current.total).toBe(3));
    expect(result.current.activeIndex).toBe(1);
    expect(result.current.activeMatchMid).toBe('m2');
  });

  it('без сообщения в адресе остаётся самое свежее', async () => {
    const { result } = mount();
    await waitFor(() => expect(result.current.total).toBe(3));
    expect(result.current.activeIndex).toBe(2);
  });

  // Сообщения может не быть среди совпадений: чат почистили, ссылку прислали
  // старую. Открыться чат обязан, и бар — работать.
  it('незнакомое сообщение не ломает бар — снова самое свежее', async () => {
    const { result } = mount({ msg: '999' });
    await waitFor(() => expect(result.current.total).toBe(3));
    expect(result.current.activeIndex).toBe(2);
  });

  // Соседняя строка той же карточки: запрос тот же, поиск не перезапускается,
  // и переставить активное совпадение больше некому.
  it('другое сообщение при том же запросе переставляет активное совпадение', async () => {
    const { result, rerender } = mount({ msg: '30' });
    await waitFor(() => expect(result.current.activeIndex).toBe(2));

    rerender({
      activeChatId: 'chat-1',
      getChats: () => [{ id: 'chat-1', messages: three, hasMore: false }],
      loadOlderMessages: vi.fn(),
      messages: three,
      find: 'жираф',
      msg: '10',
    });
    await waitFor(() => expect(result.current.activeIndex).toBe(0));
  });

  // «Назад» на ссылку с другим запросом в том же чате: совпадения на экране
  // ещё от прежнего запроса, и садиться по ним нельзя — ждём новый поиск.
  it('смена запроса и сообщения разом садится по новым совпадениям', async () => {
    const { result, rerender } = mount({ msg: '30' });
    await waitFor(() => expect(result.current.activeIndex).toBe(2));

    chatApi.searchMessages.mockResolvedValue([
      { id: 10, createdAt: '2026-01-01' },
      { id: 20, createdAt: '2026-01-02' },
    ]);
    rerender({
      activeChatId: 'chat-1',
      getChats: () => [{ id: 'chat-1', messages: three, hasMore: false }],
      loadOlderMessages: vi.fn(),
      messages: three,
      find: 'слон',
      msg: '10',
    });
    await waitFor(() => expect(result.current.total).toBe(2));
    expect(result.current.activeIndex).toBe(0);
  });

  // Стрелка — выбор человека: ответ поиска, доехавший после неё, не должен
  // вернуть на сообщение из ссылки.
  it('шаг стрелкой отменяет ожидание сообщения из адреса', async () => {
    let resolveSearch;
    chatApi.searchMessages.mockReturnValue(
      new Promise((r) => {
        resolveSearch = r;
      }),
    );
    const { result } = renderHook((p) => useInChatSearch(p), {
      initialProps: {
        activeChatId: 'chat-1',
        getChats: () => [{ id: 'chat-1', messages: three, hasMore: false }],
        loadOlderMessages: vi.fn(),
        messages: three,
        find: 'жираф',
        msg: '10',
      },
    });
    await waitFor(() => expect(chatApi.searchMessages).toHaveBeenCalled());
    act(() => result.current.goNext());
    await act(async () => resolveSearch(hits));
    expect(result.current.activeIndex).toBe(2);
  });
});
