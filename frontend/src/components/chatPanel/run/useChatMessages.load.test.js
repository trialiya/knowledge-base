import { act, renderHook } from '@testing-library/react';
import useChatMessages from './useChatMessages';
import chatApi from '@/api/chatApi';
import { RUN_KIND } from '@/constants/runKind';

vi.mock('@/api/chatApi', () => ({
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

  /**
   * Прогон, чьи события хаб уже начал вытеснять, реплеится не с начала: срезанные сегменты
   * не пришлёт никто, и ответ читался бы с полуслова до конца прогона. Такой хвост остаётся
   * на месте — он и есть единственная копия начала ответа.
   */
  test('keeps the run tail when the replay can no longer rebuild it', async () => {
    const { result, getChat } = setup({
      activeRun: { runId: 'r1', kind: 'GENERATION', replayTruncated: true },
      messages: [
        { id: 1, content: 'вопрос', type: 'USER' },
        { id: 2, content: 'начало ответа', type: 'ASSISTANT', runId: 'r1' },
      ],
    });

    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat().messages).toHaveLength(2);
  });

  /**
   * Занятость спросить не удалось — это не «чат свободен»: прогон мог идти всё это время.
   * Чат помечается неизвестной занятостью, и её переспросит поток сразу после подписки
   * (см. useChatEventStream); история при этом грузится как обычно.
   */
  test('marks the busyness unknown when the request for it fails', async () => {
    const { result, getChat } = setup({ messages: [{ id: 1, content: 'вопрос', type: 'USER' }] });
    chatApi.getActiveRun.mockRejectedValue(new Error('network'));

    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat().runStateUnknown).toBe(true);
    expect(getChat().runId).toBeNull();
    expect(getChat().messages).toHaveLength(1);
  });

  // Вкладка, открывшая чат посреди занятости, ставит таймер по elapsedMs с бэка: якорь — это
  // «сейчас минус сколько уже идёт», а не ноль. Так же и у операции: сжатие живёт десятками
  // секунд, и перезагруженная вкладка обязана продолжить отсчёт, а не начать его заново.
  test('anchors the run timer from elapsedMs of the active run', async () => {
    const { result, getChat } = setup({ activeRun: { runId: 'r2', kind: 'GENERATION', elapsedMs: 90_000 } });

    const before = Date.now();
    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat().runStartedAt).toBeGreaterThanOrEqual(before - 90_000);
    expect(getChat().runStartedAt).toBeLessThanOrEqual(Date.now() - 90_000);
  });

  test('anchors the timer of a running compaction too', async () => {
    const { result, getChat } = setup({ activeRun: { runId: 'r2', kind: 'OPERATION', elapsedMs: 12_000 } });

    const before = Date.now();
    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat().runKind).toBe('OPERATION');
    expect(getChat().runStartedAt).toBeGreaterThanOrEqual(before - 12_000);
  });

  // Вид занятости переживает перезагрузку страницы: без него чат, застигнутый посреди
  // сжатия контекста или git-команды, показал бы кнопку «остановить» (останавливать там
  // нечего) и разблокированный композер — до первого события, которого может и не быть.
  test('an active claim without elapsedMs restores the kind of run, but leaves the timer unanchored', async () => {
    const { result, getChat } = setup({ activeRun: { runId: 'r2', kind: 'OPERATION' } });

    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat().runId).toBe('r2');
    expect(getChat().runKind).toBe(RUN_KIND.OPERATION);
    expect(getChat().runStartedAt).toBeNull();
  });

  test('a free chat is restored as free, with no leftover run of its own', async () => {
    const { result, getChat } = setup({ activeRun: {} });

    await act(async () => {
      await result.current.loadMessages('c1');
    });

    expect(getChat()).toMatchObject({ runId: null, runKind: null, runStartedAt: null });
  });
});
