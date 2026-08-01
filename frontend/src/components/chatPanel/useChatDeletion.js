import { useCallback, useState } from 'react';
import chatApi from '../../api/chatApi';
import { STORAGE_KEY_ACTIVE_CHAT } from '../../constants/storage';

/**
 * Владелец подтверждения и самого удаления чата. Вынесено из ChatWindow:
 * модалка подтверждения, запрос DELETE и разбор его исхода (успех / 404 /
 * ошибка), и переключение на другой чат (или свежий черновик), если удалили
 * активный.
 *
 * Черновик (DRAFT_CHAT_ID) сюда не относится — его «удаление» лишь чистит
 * текст в композере и остаётся в ChatWindow.
 */
export default function useChatDeletion({
  chatsRef,
  activeChatId,
  selectChat,
  setChats,
  clearDraft,
  handleNewChat,
  setChatErrorModal,
  locallyDeletingRef,
}) {
  // Модалка подтверждения удаления чата: null | { id, title }
  const [chatDeleteConfirm, setChatDeleteConfirm] = useState(null);
  // Уведомление об ошибке удаления чата на сервере: null | { status }.
  const [deleteErrorNotice, setDeleteErrorNotice] = useState(null);

  // Запросить удаление реального (не-черновичного) чата: открывает модалку
  // подтверждения. Удалить последний оставшийся чат нельзя — кроме notFound
  // (локальной строки-заглушки битой ссылки, на бэкенде её и так нет).
  const requestDeleteChat = useCallback(
    (id) => {
      const chat = chatsRef.current.find((c) => c.id === id);
      if (!chat?.notFound && chatsRef.current.length <= 1) return;
      setChatDeleteConfirm({ id, title: chat?.title ?? '' });
    },
    [chatsRef],
  );

  const cancelDeleteChat = useCallback(() => setChatDeleteConfirm(null), []);

  const dismissDeleteErrorNotice = useCallback(() => setDeleteErrorNotice(null), []);

  // Реальное удаление — после подтверждения в модалке. Помечаем как «наше»
  // удаление ДО запроса — эхо CHAT_DELETED по потоку не покажет нам модалку
  // «удалён в другой вкладке» — но снимаем метку обратно, если сервер отказал:
  // иначе будущее реальное удаление этого чата (кем-то другим) молча
  // проигнорируется, как будто это снова наше же эхо.
  const confirmDeleteChat = useCallback(async () => {
    const target = chatDeleteConfirm;
    setChatDeleteConfirm(null);
    if (!target) return;
    const { id } = target;
    // NotFound-чат (битая ссылка) на бэкенде не существует — DELETE всегда вернул
    // бы 404, поэтому строку удаляем локально, без запроса к серверу.
    const chat = chatsRef.current.find((c) => c.id === id);
    const isLocalOnly = !!chat?.notFound;

    if (!isLocalOnly) {
      locallyDeletingRef.current.add(id);
      try {
        const res = await chatApi.deleteChat(id);
        if (!res.ok) {
          locallyDeletingRef.current.delete(id);
          // 404 = чата на сервере уже нет (удалён в другой сессии / битая ссылка):
          // локальное удаление всё равно корректно; остальные статусы — ошибка.
          if (res.status !== 404) {
            setDeleteErrorNotice({ status: res.status });
            return;
          }
        }
      } catch {
        locallyDeletingRef.current.delete(id);
        setDeleteErrorNotice({ status: 'network' });
        return;
      }
    }
    clearDraft(id); // черновик удалённого чата больше не нужен
    setChatErrorModal(null); // закрываем модалку «Чат не найден» для удаляемой строки
    setChats((prev) => prev.filter((item) => item.id !== id));
    if (activeChatId === id) {
      const remaining = chatsRef.current.filter((item) => item.id !== id);
      const newActiveId = remaining[0]?.id || null;
      if (newActiveId) {
        selectChat(newActiveId);
      } else {
        // Чатов не осталось: стартуем свежий черновик и чистим битый id из памяти
        // (selectChat(DRAFT_CHAT_ID) перезапишет localStorage валидным значением).
        localStorage.removeItem(STORAGE_KEY_ACTIVE_CHAT);
        handleNewChat();
      }
    }
  }, [
    chatDeleteConfirm,
    chatsRef,
    activeChatId,
    selectChat,
    setChats,
    clearDraft,
    handleNewChat,
    setChatErrorModal,
    locallyDeletingRef,
  ]);

  return {
    chatDeleteConfirm,
    deleteErrorNotice,
    requestDeleteChat,
    confirmDeleteChat,
    cancelDeleteChat,
    dismissDeleteErrorNotice,
  };
}
