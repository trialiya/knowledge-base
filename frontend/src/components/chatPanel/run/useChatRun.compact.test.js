import { renderHook, act } from '@testing-library/react';
import { vi, describe, test, expect, beforeEach } from 'vitest';
import chatApi from '@/api/chatApi';
import { RUN_KIND } from '@/constants/runKind';
import { DRAFT_CHAT_ID } from '@/constants/storage';
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

  let restoreDraft;
  let clearDraftText;
  let notify;

  const setup = (activeChatId = CHAT) => {
    const patchChat = vi.fn((id, patch) => {
      chats = chats.map((c) => (c.id === id ? { ...c, ...(typeof patch === 'function' ? patch(c) : patch) } : c));
    });
    return renderHook(() =>
      useChatRun({
        activeChatId,
        getChats: () => chats,
        setChats: vi.fn(),
        patchChat,
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
  };

  beforeEach(() => {
    vi.clearAllMocks();
    restoreDraft = vi.fn();
    clearDraftText = vi.fn();
    notify = vi.fn();
    chats = [{ id: CHAT, runId: null, messages: [{ sender: 'user', text: 'привет' }] }];
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

  // Композер стирает текст на отправке, и вернуть его может только тот, кто отказал.
  // Отказ, стоящий пользователю набранного, — худший вид отказа: в поле ввода после
  // него пусто, а сделать команда ничего не сделала.
  test('в ещё не начатом чате сжатие отклонено, и набранное возвращается в поле', async () => {
    const { result } = setup(DRAFT_CHAT_ID);

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chatApi.compact).not.toHaveBeenCalled();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ messageKey: 'compact.draftMessage' }));
    expect(restoreDraft).toHaveBeenCalled();
  });

  // id у чата бывает и без единого сообщения: его выдаёт вложение, приложенное к ещё
  // не заданному вопросу. Сжимать там нечего, и узнать это должен композер — а не
  // ответ 422 после отправки.
  test('в чате с id, но без сообщений сжатие отклонено на месте', async () => {
    chats = [{ id: CHAT, runId: null, messages: [] }];
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chatApi.compact).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
  });

  // Отказать может и сервер — тогда набранное возвращает он же. Черновик для этого
  // чистится только после реального старта, иначе возвращать было бы нечего.
  test('отказ сервера возвращает набранное и не трогает черновик', async () => {
    chatApi.compact.mockRejectedValue({ status: 422 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(clearDraftText).not.toHaveBeenCalled();
    expect(restoreDraft).toHaveBeenCalled();
  });

  test('удачный старт черновик чистит, а возвращать нечего', async () => {
    chatApi.compact.mockResolvedValue({ runId: 'op-1', messageId: 9 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(clearDraftText).toHaveBeenCalledWith(CHAT);
    expect(restoreDraft).not.toHaveBeenCalled();
  });

  // Две команды сжатия различаются для этой вкладки ровно одним флагом запроса: где провести
  // границу, решает бэк, а перепутанный здесь флаг сжёг бы ход, который просили сберечь.
  test('`/compact-1` уходит тем же запросом, но с keepLastRun', async () => {
    chatApi.compact.mockResolvedValue({ runId: 'op-1', messageId: 9 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact-1 ужми'));

    expect(chatApi.compact).toHaveBeenCalledWith(CHAT, '/compact-1 ужми', 'ужми', true, expect.any(String));
  });

  test('а `/compact` — с тем же флагом снятым', async () => {
    chatApi.compact.mockResolvedValue({ runId: 'op-1', messageId: 9 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact ужми'));

    expect(chatApi.compact).toHaveBeenCalledWith(CHAT, '/compact ужми', 'ужми', false, expect.any(String));
  });

  // «Сжимать нечего» у `/compact-1` про другое: не «контекст уже одна сводка», а «кроме
  // сбережённого хода в нём ничего и нет». Один текст на оба случая объяснял бы не тот отказ.
  test('422 у `/compact-1` объясняется своими словами', async () => {
    chatApi.compact.mockRejectedValue({ status: 422 });
    const { result } = setup();

    await act(() => result.current.sendMessage('/compact-1'));

    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ messageKey: 'compact.emptyKeepLastMessage' }));
  });
});
