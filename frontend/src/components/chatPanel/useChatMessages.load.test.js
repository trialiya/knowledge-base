import { act, renderHook } from '@testing-library/react';
import useChatMessages from './useChatMessages';
import chatApi from '../../api/chatApi';

vi.mock('../../api/chatApi', () => ({
  default: { getChatMeta: vi.fn(), getMessages: vi.fn(), getActiveRun: vi.fn() },
}));

/**
 * What `loadMessages` hands back to its caller. The chat itself is updated through
 * `setChats`, but the catch-up reconciliation in `useChatEventStream` also reads the
 * returned page: it digs the finished run's tool calls out of it to invalidate the
 * knowledge base / files caches whose live events it missed.
 */
describe('loadMessages return value', () => {
  const page = (messages, rest = {}) => ({ messages, hasMore: false, oldestCursor: null, ...rest });

  function setup({ activeRun = {}, messages = [] } = {}) {
    chatApi.getChatMeta.mockResolvedValue({});
    chatApi.getMessages.mockResolvedValue(page(messages));
    chatApi.getActiveRun.mockResolvedValue(activeRun);

    let chats = [{ id: 'c1' }];
    const setChats = vi.fn((fn) => {
      chats = typeof fn === 'function' ? fn(chats) : fn;
    });
    const { result } = renderHook(() =>
      useChatMessages({
        chats: [],
        getChats: () => chats,
        setChats,
        activeChatId: null,
        onLoadError: vi.fn(),
      }),
    );
    return { result, getChat: () => chats[0] };
  }

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('a parallel call gets the same load, not undefined', async () => {
    const { result } = setup({ messages: [{ id: 1, content: 'вопрос', type: 'USER' }] });

    let first;
    let second;
    await act(async () => {
      first = result.current.loadMessages('c1');
      second = result.current.loadMessages('c1');
      await Promise.all([first, second]);
    });

    expect(chatApi.getMessages).toHaveBeenCalledTimes(1); // загрузка по-прежнему одна
    expect(await second).toBe(await first);
    expect(await second).toHaveLength(1);
  });

  test('returns the page untrimmed while the chat keeps the trimmed tail', async () => {
    // Хвост после последнего USER срезается из показанной истории на время активного
    // прогона (его пересоберёт реплей). Но вызовы инструментов ЗАВЕРШИВШЕГОСЯ прогона
    // живут как раз там, когда следующий запущен повтором (RETRY_MODE.CONTINUE) — он
    // не добавляет нового USER-сообщения. Вернуть обрезанное значило бы потерять их.
    const { result, getChat } = setup({
      activeRun: { runId: 'r2' },
      messages: [
        { id: 1, content: 'вопрос', type: 'USER' },
        { id: 2, content: 'ответ', type: 'ASSISTANT', runId: 'r1' },
      ],
    });

    let returned;
    await act(async () => {
      returned = await result.current.loadMessages('c1');
    });

    expect(returned).toHaveLength(2);
    expect(getChat().messages).toHaveLength(1); // в чате — обрезанная история
  });
});
