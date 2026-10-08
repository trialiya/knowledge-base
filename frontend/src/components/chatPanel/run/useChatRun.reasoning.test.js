import { renderHook, act } from '@testing-library/react';
import { vi, describe, test, expect, beforeEach } from 'vitest';
import chatApi from '@/api/chatApi';
import useChatRun from './useChatRun';

vi.mock('@/api/chatApi', () => ({
  default: { queueMessage: vi.fn(), startRun: vi.fn(), getActiveRun: vi.fn() },
}));

/**
 * Какой уровень рассуждений уходит с сообщением. Выбор хранится у чата и переживает смену
 * модели, поэтому уходит он, только когда у модели этой отправки такой уровень есть; иначе
 * поле не названо, и бэк сам возьмёт умолчание модели — то же, что показывает селектор.
 */
describe('useChatRun — уровень рассуждений', () => {
  const CHAT = 'conv-1';
  let chats;

  const setup = () => {
    const setChats = vi.fn((fn) => {
      chats = typeof fn === 'function' ? fn(chats) : fn;
    });
    return renderHook(() =>
      useChatRun({
        activeChatId: CHAT,
        getChats: () => chats,
        setChats,
        patchChat: vi.fn(),
        patchMessages: vi.fn(),
        selectChat: vi.fn(),
        clearDraft: vi.fn(),
        clearDraftText: vi.fn(),
        restoreDraft: vi.fn(),
        getStagedFor: () => [],
        modelConfig: {
          defaultModel: { id: 'gpt', reasoning: { default: 'medium', levels: [{ id: 'low' }, { id: 'medium' }] } },
          models: [{ id: 'plain' }],
        },
        modelOptions: [{ id: 'gpt' }, { id: 'plain' }],
        modeOptions: [],
        projectOptions: [{ id: 'kb' }],
        defaultProjectId: 'kb',
        notify: vi.fn(),
      }),
    );
  };

  beforeEach(() => {
    vi.clearAllMocks();
    chatApi.startRun.mockResolvedValue({ runId: 'r1', messageId: 5 });
    chatApi.queueMessage.mockResolvedValue(undefined);
  });

  test('уровень, который у модели есть, уходит и в прогон, и в очередь', async () => {
    chats = [{ id: CHAT, model: 'gpt', reasoning: 'low', runId: null, messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('вопрос'));
    expect(chatApi.startRun).toHaveBeenCalledWith(CHAT, 'вопрос', expect.objectContaining({ reasoning: 'low' }));

    chats = [{ ...chats[0], runId: 'r1' }];
    await act(() => result.current.sendMessage('ещё'));
    expect(chatApi.queueMessage).toHaveBeenCalledWith(CHAT, 'r1', 'ещё', expect.objectContaining({ reasoning: 'low' }));
  });

  test('без выбора у чата уходит явный сброс — пустая строка, а не «не названо»', async () => {
    chats = [{ id: CHAT, model: 'gpt', reasoning: null, runId: null, messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('вопрос'));

    expect(chatApi.startRun).toHaveBeenCalledWith(CHAT, 'вопрос', expect.objectContaining({ reasoning: '' }));
  });

  test('у модели без такого уровня поле не названо', async () => {
    chats = [{ id: CHAT, model: 'plain', reasoning: 'low', runId: null, messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('вопрос'));

    expect(chatApi.startRun).toHaveBeenCalledWith(CHAT, 'вопрос', expect.objectContaining({ reasoning: null }));
    // Выбор чата не трогаем: вернувшись на модель с этим уровнем, чат снова пойдёт на нём.
    expect(chats[0].reasoning).toBe('low');
  });
});
