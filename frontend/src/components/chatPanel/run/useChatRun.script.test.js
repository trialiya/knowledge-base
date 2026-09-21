import { renderHook, act } from '@testing-library/react';
import { vi, describe, test, expect, beforeEach } from 'vitest';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';
import useChatRun from './useChatRun';

vi.mock('@/api/chatApi', () => ({
  default: { runScript: vi.fn(), compact: vi.fn(), startRun: vi.fn(), queueMessage: vi.fn(), getActiveRun: vi.fn() },
}));

/**
 * Команда `/script`: сам прогон приезжает рядом истории и событием, а вкладке остаётся ровно одно —
 * отказ. Набранное при отказе возвращается в поле: композер стёр его на отправке, и другого места,
 * откуда его достать, нет.
 */
describe('useChatRun — команда /script', () => {
  const CHAT = 'conv-1';
  let chats;

  let restoreDraft;
  let clearDraftText;
  let notify;

  const setup = (activeChatId = CHAT) =>
    renderHook(() =>
      useChatRun({
        activeChatId,
        getChats: () => chats,
        setChats: vi.fn(),
        patchChat: vi.fn(),
        patchMessages: vi.fn(),
        selectChat: vi.fn(),
        clearDraft: vi.fn(),
        clearDraftText,
        restoreDraft,
        getStagedFor: () => [],
        modelConfig: { defaultModel: { id: 'gpt' } },
        modelOptions: [{ id: 'gpt' }],
        modeOptions: [],
        projectOptions: [{ id: 'kb' }],
        defaultProjectId: 'kb',
        notify,
      }),
    );

  beforeEach(() => {
    vi.clearAllMocks();
    restoreDraft = vi.fn();
    clearDraftText = vi.fn();
    notify = vi.fn();
    chats = [{ id: CHAT, runId: null, messages: [{ sender: 'user', text: 'привет' }] }];
  });

  test('состоявшийся прогон уходит своим эндпоинтом и гасит черновик', async () => {
    chatApi.runScript.mockResolvedValue({ project: 'kb' });
    const { result } = setup();

    await act(() => result.current.sendMessage('/script report area=docs'));

    expect(chatApi.runScript).toHaveBeenCalledWith(CHAT, 'report', { area: 'docs' });
    expect(clearDraftText).toHaveBeenCalledWith(CHAT);
    expect(restoreDraft).not.toHaveBeenCalled();
  });

  // Тот же порядок, что у `/compact`, и по той же причине: чистить черновик до ответа значит
  // терять набранное на каждом отказе — restoreDraft читает именно его, и прочитает пустоту.
  test('отказ сервера возвращает набранное и не трогает черновик', async () => {
    chatApi.runScript.mockRejectedValue(new Error('Unknown script "repoort"'));
    const { result } = setup();

    await act(() => result.current.sendMessage('/script repoort'));

    expect(clearDraftText).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ titleKey: 'script.failedTitle' }));
  });

  test('в ещё не начатом чате команда отклонена на месте', async () => {
    const { result } = setup(DRAFT_CHAT_ID);

    await act(() => result.current.sendMessage('/script report'));

    expect(chatApi.runScript).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
  });

  // Аргумент без `=` под своим именем с пустым значением подменил бы объявленное умолчание:
  // команда сработала бы, сделав не то. Поэтому отказ, а не догадка.
  test('аргумент без = отклонён до запроса', async () => {
    const { result } = setup();

    await act(() => result.current.sendMessage('/script report docs'));

    expect(chatApi.runScript).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ messageKey: 'script.badArgumentMessage' }));
    expect(restoreDraft).toHaveBeenCalled();
  });
});
