import { renderHook, act } from '@testing-library/react';
import useChatDeletion from './useChatDeletion';
import { chatDeleteErrorNotice } from '../run/chatNotices';
import chatApi from '../../../api/chatApi';

vi.mock('../../../api/chatApi', () => ({ default: { deleteChat: vi.fn() } }));

function setup(chats, overrides = {}) {
  let list = chats;
  const getChats = () => list;
  const selectChat = vi.fn();
  const setChats = vi.fn((updater) => {
    list = typeof updater === 'function' ? updater(list) : updater;
  });
  const clearDraft = vi.fn();
  const handleNewChat = vi.fn();
  const notify = vi.fn();
  const dismissNotice = vi.fn();

  const { result } = renderHook(() =>
    useChatDeletion({
      getChats,
      activeChatId: overrides.activeChatId ?? chats[0]?.id ?? null,
      selectChat,
      setChats,
      clearDraft,
      handleNewChat,
      notify,
      dismissNotice,
      ...overrides.hookOverrides,
    }),
  );

  return { result, getChats, selectChat, setChats, clearDraft, handleNewChat, notify, dismissNotice };
}

describe('useChatDeletion', () => {
  afterEach(() => {
    vi.resetAllMocks();
  });

  describe('requestDeleteChat', () => {
    it('не открывает подтверждение для единственного обычного чата', () => {
      const { result } = setup([{ id: 'c1', title: 'Единственный' }]);

      act(() => result.current.requestDeleteChat('c1'));

      expect(result.current.chatDeleteConfirm).toBeNull();
    });

    it('открывает подтверждение для единственного notFound-чата (заглушки битой ссылки)', () => {
      const { result } = setup([{ id: 'c1', title: '...', notFound: true }]);

      act(() => result.current.requestDeleteChat('c1'));

      expect(result.current.chatDeleteConfirm).toEqual({ id: 'c1', title: '...' });
    });

    it('открывает подтверждение, когда чатов несколько', () => {
      const { result } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));

      expect(result.current.chatDeleteConfirm).toEqual({ id: 'c2', title: 'Второй' });
    });
  });

  describe('confirmDeleteChat — notFound-чат (локальная заглушка)', () => {
    it('удаляет строку локально без запроса к серверу и закрывает модалку «не найдено»', async () => {
      const { result, setChats, dismissNotice, clearDraft } = setup([{ id: 'c1', title: '...', notFound: true }]);

      act(() => result.current.requestDeleteChat('c1'));
      await act(async () => result.current.confirmDeleteChat());

      expect(chatApi.deleteChat).not.toHaveBeenCalled();
      expect(clearDraft).toHaveBeenCalledWith('c1');
      expect(dismissNotice).toHaveBeenCalled();
      expect(setChats).toHaveBeenCalled();
      expect(result.current.chatDeleteConfirm).toBeNull();
    });
  });

  describe('confirmDeleteChat — реальный чат на сервере', () => {
    it('успешный DELETE убирает чат из списка', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: true, status: 204 });
      const { result, setChats, notify } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(chatApi.deleteChat).toHaveBeenCalledWith('c2');
      expect(setChats).toHaveBeenCalled();
      expect(notify).not.toHaveBeenCalled();
      // Метка «наше удаление» остаётся: эхо CHAT_DELETED по этому чату — наше,
      // и модалку «удалён в другой вкладке» показывать не нужно.
      expect(result.current.consumeLocalDeletion('c2')).toBe(true);
    });

    it('404 трактуется как «уже удалён» — чат убирается локально, ошибка не показывается', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: false, status: 404 });
      const { result, setChats, notify } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(setChats).toHaveBeenCalled();
      expect(notify).not.toHaveBeenCalled();
      // Как и при успехе: строку убрали мы, поэтому метку не снимаем — иначе
      // эхо CHAT_DELETED сработало бы как «удалён в другой вкладке».
      expect(result.current.consumeLocalDeletion('c2')).toBe(true);
    });

    it('прочий отказ сервера показывает ошибку и НЕ убирает чат из списка', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: false, status: 500 });
      const { result, setChats, notify } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(notify).toHaveBeenCalledWith(chatDeleteErrorNotice(500));
      expect(setChats).not.toHaveBeenCalled();
      // Метка «наше удаление» снята — реальное эхо CHAT_DELETED для этого чата
      // (если он всё же был удалён кем-то ещё) не окажется молча проглочено.
      expect(result.current.consumeLocalDeletion('c2')).toBe(false);
    });

    it('сетевой сбой показывает ошибку network и НЕ убирает чат из списка', async () => {
      chatApi.deleteChat.mockRejectedValue(new Error('network down'));
      const { result, setChats, notify } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(notify).toHaveBeenCalledWith(chatDeleteErrorNotice('network'));
      expect(setChats).not.toHaveBeenCalled();
    });
  });

  describe('confirmDeleteChat — выбор активного чата после удаления', () => {
    it('переключается на первый оставшийся чат, если удалили активный', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: true, status: 204 });
      const { result, selectChat, handleNewChat } = setup(
        [
          { id: 'c1', title: 'Первый' },
          { id: 'c2', title: 'Второй' },
        ],
        { activeChatId: 'c1' },
      );

      act(() => result.current.requestDeleteChat('c1'));
      await act(async () => result.current.confirmDeleteChat());

      expect(selectChat).toHaveBeenCalledWith('c2');
      expect(handleNewChat).not.toHaveBeenCalled();
    });

    it('стартует свежий черновик, если удалили последний (notFound) чат', async () => {
      const { result, selectChat, handleNewChat } = setup([{ id: 'c1', title: '...', notFound: true }], {
        activeChatId: 'c1',
      });

      act(() => result.current.requestDeleteChat('c1'));
      await act(async () => result.current.confirmDeleteChat());

      // Битый id в памяти последнего чата перезапишет сам handleNewChat через
      // selectChat(DRAFT_CHAT_ID) — отдельной чистки localStorage тут нет.
      expect(selectChat).not.toHaveBeenCalled();
      expect(handleNewChat).toHaveBeenCalled();
    });

    it('не трогает выбор активного чата, если удалили не активный', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: true, status: 204 });
      const { result, selectChat, handleNewChat } = setup(
        [
          { id: 'c1', title: 'Первый' },
          { id: 'c2', title: 'Второй' },
        ],
        { activeChatId: 'c1' },
      );

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(selectChat).not.toHaveBeenCalled();
      expect(handleNewChat).not.toHaveBeenCalled();
    });
  });

  it('cancelDeleteChat закрывает модалку подтверждения', () => {
    const { result } = setup([{ id: 'c1', title: '...', notFound: true }]);

    act(() => result.current.requestDeleteChat('c1'));
    expect(result.current.chatDeleteConfirm).not.toBeNull();

    act(() => result.current.cancelDeleteChat());
    expect(result.current.chatDeleteConfirm).toBeNull();
  });

  // Эхо CHAT_DELETED приходит ровно один раз, поэтому метка одноразовая:
  // оставшись, она проглотила бы следующее удаление того же id — уже чужое.
  it('consumeLocalDeletion снимает метку «наше удаление» с первого раза', async () => {
    chatApi.deleteChat.mockResolvedValue({ ok: true, status: 204 });
    const { result } = setup([
      { id: 'c1', title: 'Первый' },
      { id: 'c2', title: 'Второй' },
    ]);

    act(() => result.current.requestDeleteChat('c2'));
    await act(async () => result.current.confirmDeleteChat());

    expect(result.current.consumeLocalDeletion('c2')).toBe(true);
    expect(result.current.consumeLocalDeletion('c2')).toBe(false);
  });
});
