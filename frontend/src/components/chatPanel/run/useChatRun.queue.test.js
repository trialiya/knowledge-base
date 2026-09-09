import { renderHook, act } from '@testing-library/react';
import { vi, describe, test, expect, beforeEach } from 'vitest';
import chatApi from '@/api/chatApi';
import useChatRun from './useChatRun';

vi.mock('@/api/chatApi', () => ({
  default: { queueMessage: vi.fn(), startRun: vi.fn(), getActiveRun: vi.fn() },
}));

/**
 * Куда уходит сообщение, отправленное во время ответа. Развилка одна и молчаливая: ошибись
 * здесь — и сообщение либо получит 409 вместо доставки, либо задвоится вторым прогоном.
 */
describe('useChatRun — отправка во время прогона', () => {
  const CHAT = 'conv-1';
  let chats;
  let clearDraft;
  let clearDraftText;
  let restoreDraft;

  const setup = () => {
    const setChats = vi.fn((fn) => {
      chats = typeof fn === 'function' ? fn(chats) : fn;
    });
    const patchMessages = vi.fn((id, fn) => {
      chats = chats.map((c) => (c.id === id ? { ...c, messages: fn(c.messages || []) } : c));
    });
    const hook = renderHook(() =>
      useChatRun({
        activeChatId: CHAT,
        getChats: () => chats,
        setChats,
        patchChat: vi.fn(),
        patchMessages,
        selectChat: vi.fn(),
        clearDraft,
        clearDraftText,
        restoreDraft,
        getStagedFor: () => [],
        modelConfig: { defaultModel: { id: 'gpt' } },
        modelOptions: [{ id: 'gpt' }],
        modeOptions: [],
        projectOptions: [{ id: 'kb' }],
        defaultProjectId: 'kb',
        notify: vi.fn(),
      }),
    );
    return hook;
  };

  const messages = () => chats[0].messages;

  beforeEach(() => {
    vi.clearAllMocks();
    clearDraft = vi.fn();
    clearDraftText = vi.fn();
    restoreDraft = vi.fn();
    chatApi.startRun.mockResolvedValue({ runId: 'r2', messageId: 5 });
  });

  test('пока идёт прогон, сообщение уходит в его очередь, а не новым прогоном', async () => {
    chats = [{ id: CHAT, runId: 'r1', messages: [] }];
    chatApi.queueMessage.mockResolvedValue(undefined);
    const { result } = setup();

    await act(() => result.current.sendMessage('и добавь тесты'));

    expect(chatApi.queueMessage).toHaveBeenCalledWith(CHAT, 'r1', 'и добавь тесты', expect.anything());
    expect(chatApi.startRun).not.toHaveBeenCalled();
    // Ряда истории у поставленного в очередь ещё нет — пузырь ждёт доставки.
    expect(messages().at(-1)).toMatchObject({ text: 'и добавь тесты', queued: true });
  });

  test('без прогона путь прежний — обычный POST /runs', async () => {
    chats = [{ id: CHAT, runId: null, messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('вопрос'));

    expect(chatApi.queueMessage).not.toHaveBeenCalled();
    expect(chatApi.startRun).toHaveBeenCalled();
    expect(messages().at(-1).queued).toBeUndefined();
  });

  /**
   * Прогон кончился, пока набирали. Пузырь уже показан «ожидающим» — снимаем пометку и
   * отправляем обычным путём: иначе сообщение осталось бы висеть ожиданием навсегда.
   */
  test('409 переводит отправку на обычный путь и снимает «ожидает»', async () => {
    chats = [{ id: CHAT, runId: 'r1', messages: [] }];
    chatApi.queueMessage.mockRejectedValue({ status: 409 });
    const { result } = setup();

    await act(() => result.current.sendMessage('и добавь тесты'));

    expect(chatApi.startRun).toHaveBeenCalled();
    expect(messages().at(-1)).toMatchObject({ text: 'и добавь тесты' });
    expect(messages().at(-1).queued).toBeUndefined();
  });

  /**
   * Сбой запроса — пузырь снимаем (он обещал бы доставку, которой не будет), но набранное не
   * теряем: черновик остаётся нетронутым, и поле ввода перечитывает его по сигналу.
   */
  test('сбой постановки в очередь снимает пузырь и возвращает набранное', async () => {
    chats = [{ id: CHAT, runId: 'r1', messages: [] }];
    chatApi.queueMessage.mockRejectedValue({ status: 500 });
    const { result } = setup();

    await act(() => result.current.sendMessage('и добавь тесты'));

    expect(chatApi.startRun).not.toHaveBeenCalled();
    expect(messages()).toHaveLength(0);
    expect(clearDraft).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
  });

  /** Команду чату в очередь не поставишь: у сжатия нет терминальной обработки, которая её опустошит. */
  test('/compact во время ответа отклоняется и не съедает набранное', async () => {
    chats = [{ id: CHAT, runId: 'r1', messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chatApi.queueMessage).not.toHaveBeenCalled();
    expect(clearDraftText).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
  });
});

/**
 * Черновик получает настоящий id при первой отправке. Это смена id у уже открытого
 * чата, а не переход: записи истории быть не должно — «Назад» вело бы на черновик,
 * которого уже нет.
 */
describe('useChatRun — продвижение черновика', () => {
  test('реальный id уходит в навигацию фоном, без перехода', async () => {
    let chats = [{ id: 'new', runId: null, messages: [] }];
    const selectChat = vi.fn();
    chatApi.startRun.mockResolvedValue({ runId: 'r1', messageId: 1 });
    const { result } = renderHook(() =>
      useChatRun({
        activeChatId: 'new',
        getChats: () => chats,
        setChats: vi.fn((fn) => {
          chats = typeof fn === 'function' ? fn(chats) : fn;
        }),
        patchChat: vi.fn(),
        patchMessages: vi.fn(),
        selectChat,
        clearDraft: vi.fn(),
        clearDraftText: vi.fn(),
        restoreDraft: vi.fn(),
        getStagedFor: () => [],
        modelConfig: { defaultModel: { id: 'gpt' } },
        modelOptions: [{ id: 'gpt' }],
        modeOptions: [],
        projectOptions: [{ id: 'kb' }],
        defaultProjectId: 'kb',
        notify: vi.fn(),
      }),
    );

    await act(() => result.current.sendMessage('первый вопрос'));

    expect(selectChat).toHaveBeenCalledTimes(1);
    expect(selectChat).toHaveBeenCalledWith(expect.any(String), { navigate: false });
    expect(selectChat.mock.calls[0][0]).not.toBe('new');
  });
});
