import { renderHook, act } from '@testing-library/react';
import { vi, describe, test, expect, beforeEach } from 'vitest';
import chatApi from '@/api/chatApi';
import { RUN_KIND } from '@/constants/runKind';
import useChatRun from './useChatRun';

vi.mock('@/api/chatApi', () => ({
  default: { compact: vi.fn(), startRun: vi.fn(), queueMessage: vi.fn(), getActiveRun: vi.fn() },
}));

/**
 * Занятость чата на время `/compact`: команда уходит своим эндпоинтом, а показать её приходится
 * тем же, чем показан обычный прогон, — и якорем таймера в том числе. Своего прогона у операции
 * нет, отсчитывать «сколько уже идёт» больше нечем.
 */
describe('useChatRun — старт сжатия', () => {
  const CHAT = 'conv-1';
  let chats;

  const setup = () => {
    const patchChat = vi.fn((id, patch) => {
      chats = chats.map((c) => (c.id === id ? { ...c, ...(typeof patch === 'function' ? patch(c) : patch) } : c));
    });
    return renderHook(() =>
      useChatRun({
        activeChatId: CHAT,
        getChats: () => chats,
        setChats: vi.fn(),
        patchChat,
        patchMessages: vi.fn(),
        selectChat: vi.fn(),
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
  };

  beforeEach(() => {
    vi.clearAllMocks();
    chats = [{ id: CHAT, runId: null, messages: [] }];
  });

  // Ждать COMPACT_STARTED нельзя: событие идёт своим путём и может отстать, а плашка «сжимаю…»
  // без отсчёта неотличима от зависшей — у самой долгой операции чата.
  test('якорь таймера ставится ответом на команду, не дожидаясь COMPACT_STARTED', async () => {
    chatApi.compact.mockResolvedValue({ runId: 'op-1', messageId: 9 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chats[0]).toMatchObject({ runId: 'op-1', runKind: RUN_KIND.OPERATION });
    expect(chats[0].runStartedAt).toEqual(expect.any(Number));
  });

  // COMPACT_STARTED вполне может опередить ответ на команду — тогда якорь уже стоит, и сдвигать
  // его нельзя: таймер прыгнул бы назад посреди отсчёта.
  test('но уже поставленный якорь не сдвигает: событие могло опередить ответ', async () => {
    chatApi.compact.mockImplementation(async () => {
      chats = chats.map((c) => (c.id === CHAT ? { ...c, runStartedAt: 1000 } : c));
      return { runId: 'op-1', messageId: 9 };
    });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chats[0].runStartedAt).toBe(1000);
  });
});
