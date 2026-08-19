import { useCallback, useRef, useState } from 'react';
import chatApi from '@/api/chatApi';
import { chatDeleteErrorNotice } from '../run/chatNotices';

/**
 * Владелец подтверждения и самого удаления чата. Вынесено из ChatWindow:
 * модалка подтверждения, запрос DELETE и разбор его исхода (успех / 404 /
 * ошибка), и переключение на другой чат (или свежий черновик), если удалили
 * активный.
 *
 * Черновик (DRAFT_CHAT_ID) сюда не относится — его «удаление» лишь чистит
 * текст в композере и остаётся в ChatWindow.
 *
 * @param {object}   p
 * @param {Function} p.getChats       () => чаты: свежий снимок списка
 * @param {string}   p.activeChatId
 * @param {Function} p.selectChat
 * @param {Function} p.setChats
 * @param {Function} p.clearDraft
 * @param {Function} p.handleNewChat  стартовать черновик, когда чатов не осталось
 * @param {Function} p.notify         (дескриптор) => void — см. chatNotices
 * @param {Function} p.dismissNotice  закрыть открытое уведомление
 */
export default function useChatDeletion({
  getChats,
  activeChatId,
  selectChat,
  setChats,
  clearDraft,
  handleNewChat,
  notify,
  dismissNotice,
}) {
  // Модалка подтверждения удаления чата: null | { id, title }
  const [chatDeleteConfirm, setChatDeleteConfirm] = useState(null);

  // id чатов, которые удаляем из ЭТОЙ вкладки — чтобы не показать себе же модалку
  // «удалён в другой вкладке», получив собственное эхо CHAT_DELETED. Ref живёт
  // здесь, а не у вызывающего: ставит метку удаление, а снимает её обработчик
  // события — обоим достаточно consumeLocalDeletion.
  const locallyDeletingRef = useRef(new Set());

  /**
   * Наше ли это удаление? Метка при этом снимается: эхо приходит ровно один раз,
   * а оставленная метка проглотила бы следующее — уже действительно чужое.
   */
  const consumeLocalDeletion = useCallback((id) => locallyDeletingRef.current.delete(id), []);

  // Запросить удаление реального (не-черновичного) чата: открывает модалку
  // подтверждения. Удалить последний оставшийся чат нельзя — кроме notFound
  // (локальной строки-заглушки битой ссылки, на бэкенде её и так нет).
  const requestDeleteChat = useCallback(
    (id) => {
      const chats = getChats();
      const chat = chats.find((c) => c.id === id);
      if (!chat?.notFound && chats.length <= 1) return;
      setChatDeleteConfirm({ id, title: chat?.title ?? '' });
    },
    [getChats],
  );

  const cancelDeleteChat = useCallback(() => setChatDeleteConfirm(null), []);

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
    const chat = getChats().find((c) => c.id === id);
    const isLocalOnly = !!chat?.notFound;

    if (!isLocalOnly) {
      locallyDeletingRef.current.add(id);
      try {
        const res = await chatApi.deleteChat(id);
        // 404 = чата на сервере уже нет (удалён в другой сессии / битая ссылка):
        // локальное удаление всё равно корректно; остальные статусы — ошибка.
        // Метку «наше удаление» снимаем ТОЛЬКО на реальной ошибке: на 404 мы
        // строку всё равно убираем, и пришедшее следом эхо CHAT_DELETED по этому
        // чату должно остаться «нашим», иначе выскочит модалка «удалён в другой
        // вкладке» поверх удаления, которое пользователь только что подтвердил.
        if (!res.ok && res.status !== 404) {
          locallyDeletingRef.current.delete(id);
          notify(chatDeleteErrorNotice(res.status));
          return;
        }
      } catch {
        locallyDeletingRef.current.delete(id);
        notify(chatDeleteErrorNotice('network'));
        return;
      }
    }
    clearDraft(id); // черновик удалённого чата больше не нужен
    // Открытое уведомление всегда про активный чат и своего id не хранит: после
    // удаления строки ему уже не о чем сообщать — закрываем.
    dismissNotice();
    setChats((prev) => prev.filter((item) => item.id !== id));
    if (activeChatId === id) {
      const remaining = getChats().filter((item) => item.id !== id);
      const newActiveId = remaining[0]?.id || null;
      if (newActiveId) {
        selectChat(newActiveId);
      } else {
        // Чатов не осталось: стартуем свежий черновик. Битый id из памяти чистить
        // отдельно не нужно — handleNewChat зовёт selectChat(DRAFT_CHAT_ID), а тот
        // сразу перезаписывает localStorage валидным значением.
        handleNewChat();
      }
    }
  }, [
    chatDeleteConfirm,
    getChats,
    activeChatId,
    selectChat,
    setChats,
    clearDraft,
    handleNewChat,
    notify,
    dismissNotice,
  ]);

  return { chatDeleteConfirm, requestDeleteChat, confirmDeleteChat, cancelDeleteChat, consumeLocalDeletion };
}
