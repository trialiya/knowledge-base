import { renderHook, act } from '@testing-library/react';
import useChatDeletion from './useChatDeletion';
import chatApi from '../../api/chatApi';
import { STORAGE_KEY_ACTIVE_CHAT } from '../../constants/storage';

vi.mock('../../api/chatApi', () => ({ default: { deleteChat: vi.fn() } }));

function setup(chats, overrides = {}) {
  const chatsRef = { current: chats };
  const locallyDeletingRef = { current: new Set() };
  const selectChat = vi.fn();
  const setChats = vi.fn((updater) => {
    chatsRef.current = typeof updater === 'function' ? updater(chatsRef.current) : updater;
  });
  const clearDraft = vi.fn();
  const handleNewChat = vi.fn();
  const setChatErrorModal = vi.fn();

  const { result } = renderHook(() =>
    useChatDeletion({
      chatsRef,
      activeChatId: overrides.activeChatId ?? chats[0]?.id ?? null,
      selectChat,
      setChats,
      clearDraft,
      handleNewChat,
      setChatErrorModal,
      locallyDeletingRef,
      ...overrides.hookOverrides,
    }),
  );

  return { result, chatsRef, locallyDeletingRef, selectChat, setChats, clearDraft, handleNewChat, setChatErrorModal };
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
      const { result, setChats, setChatErrorModal, clearDraft } = setup([{ id: 'c1', title: '...', notFound: true }]);

      act(() => result.current.requestDeleteChat('c1'));
      await act(async () => result.current.confirmDeleteChat());

      expect(chatApi.deleteChat).not.toHaveBeenCalled();
      expect(clearDraft).toHaveBeenCalledWith('c1');
      expect(setChatErrorModal).toHaveBeenCalledWith(null);
      expect(setChats).toHaveBeenCalled();
      expect(result.current.chatDeleteConfirm).toBeNull();
    });
  });

  describe('confirmDeleteChat — реальный чат на сервере', () => {
    it('успешный DELETE убирает чат из списка', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: true, status: 204 });
      const { result, setChats } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(chatApi.deleteChat).toHaveBeenCalledWith('c2');
      expect(setChats).toHaveBeenCalled();
      expect(result.current.deleteErrorNotice).toBeNull();
    });

    it('404 трактуется как «уже удалён» — чат убирается локально, ошибка не показывается', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: false, status: 404 });
      const { result, setChats } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(setChats).toHaveBeenCalled();
      expect(result.current.deleteErrorNotice).toBeNull();
    });

    it('прочий отказ сервера показывает ошибку и НЕ убирает чат из списка', async () => {
      chatApi.deleteChat.mockResolvedValue({ ok: false, status: 500 });
      const { result, setChats, locallyDeletingRef } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(result.current.deleteErrorNotice).toEqual({ status: 500 });
      expect(setChats).not.toHaveBeenCalled();
      // Метка «наше удаление» снята — реальное эхо CHAT_DELETED для этого чата
      // (если он всё же был удалён кем-то ещё) не окажется молча проглочено.
      expect(locallyDeletingRef.current.has('c2')).toBe(false);
    });

    it('сетевой сбой показывает ошибку network и НЕ убирает чат из списка', async () => {
      chatApi.deleteChat.mockRejectedValue(new Error('network down'));
      const { result, setChats } = setup([
        { id: 'c1', title: 'Первый' },
        { id: 'c2', title: 'Второй' },
      ]);

      act(() => result.current.requestDeleteChat('c2'));
      await act(async () => result.current.confirmDeleteChat());

      expect(result.current.deleteErrorNotice).toEqual({ status: 'network' });
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

    it('стартует свежий черновик и чистит localStorage, если удалили последний (notFound) чат', async () => {
      const removeItemSpy = vi.spyOn(Storage.prototype, 'removeItem');
      const { result, selectChat, handleNewChat } = setup([{ id: 'c1', title: '...', notFound: true }], {
        activeChatId: 'c1',
      });

      act(() => result.current.requestDeleteChat('c1'));
      await act(async () => result.current.confirmDeleteChat());

      expect(selectChat).not.toHaveBeenCalled();
      expect(handleNewChat).toHaveBeenCalled();
      expect(removeItemSpy).toHaveBeenCalledWith(STORAGE_KEY_ACTIVE_CHAT);
      removeItemSpy.mockRestore();
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

  it('dismissDeleteErrorNotice закрывает уведомление об ошибке удаления', async () => {
    chatApi.deleteChat.mockResolvedValue({ ok: false, status: 500 });
    const { result } = setup([
      { id: 'c1', title: 'Первый' },
      { id: 'c2', title: 'Второй' },
    ]);

    act(() => result.current.requestDeleteChat('c2'));
    await act(async () => result.current.confirmDeleteChat());
    expect(result.current.deleteErrorNotice).not.toBeNull();

    act(() => result.current.dismissDeleteErrorNotice());
    expect(result.current.deleteErrorNotice).toBeNull();
  });
});
