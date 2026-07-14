import { renderHook, waitFor, act } from '@testing-library/react';
import useInChatSearch, { resolveActiveMatchMid } from './useInChatSearch';
import chatApi from '../../api/chatApi';

jest.mock('../../api/chatApi');

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
  afterEach(() => jest.resetAllMocks());

  // Регрессия: дефолтный (самый свежий) хит уже загружен и виден в свежем `messages`,
  // но chatsRef ещё отстаёт на рендер (обновляется отдельным эффектом в ChatWindow).
  // Раньше первичная проверка догрузки смотрела только в chatsRef и по ошибке
  // стартовала лишнюю loadOlderMessages — та вставляла старые сообщения мимо
  // scroll-preserving логики MessageList, из-за чего уже подсвеченное сообщение
  // мгновенно уезжало из вьюпорта.
  it('не вызывает loadOlderMessages, если совпадение уже есть в свежих messages', async () => {
    const messages = [loaded('m1', 10, 'про жирафов')];
    // chatsRef «отстал» — там ещё нет сообщений этого чата.
    const chatsRef = { current: [{ id: 'chat-1', messages: [], hasMore: true }] };
    const loadOlderMessages = jest.fn().mockResolvedValue(true);
    chatApi.searchMessages.mockResolvedValue([{ id: 10, createdAt: '2026-01-01' }]);

    const { result } = renderHook(() =>
      useInChatSearch({ activeChatId: 'chat-1', chatsRef, loadOlderMessages, messages }),
    );

    act(() => result.current.openWithQuery('жираф'));

    await waitFor(() => expect(result.current.activeMatchMid).toBe('m1'));
    expect(loadOlderMessages).not.toHaveBeenCalled();
  });

  // Регрессия: удаление активного чата переключает activeChatId на другой чат, чьи
  // messages ещё не загружены (undefined), пока activeMatch с прошлого чата ещё не
  // сброшен (сброс произойдёт в отдельном эффекте на следующем рендере). Раньше
  // первичная проверка звала messages.some(...) напрямую и падала с TypeError.
  it('не падает, если messages стал undefined при смене чата (удаление активного чата)', async () => {
    const messages = [loaded('m1', 10, 'про жирафов')];
    const chatsRef = { current: [{ id: 'chat-1', messages, hasMore: false }] };
    const loadOlderMessages = jest.fn().mockResolvedValue(true);
    chatApi.searchMessages.mockResolvedValue([{ id: 10, createdAt: '2026-01-01' }]);

    const { result, rerender } = renderHook((props) => useInChatSearch(props), {
      initialProps: { activeChatId: 'chat-1', chatsRef, loadOlderMessages, messages },
    });

    act(() => result.current.openWithQuery('жираф'));
    await waitFor(() => expect(result.current.activeMatchMid).toBe('m1'));

    chatsRef.current = [{ id: 'chat-2', messages: undefined, hasMore: true }];
    expect(() =>
      rerender({ activeChatId: 'chat-2', chatsRef, loadOlderMessages, messages: undefined }),
    ).not.toThrow();
  });
});
