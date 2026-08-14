import { useCallback, useState } from 'react';
import attachmentApi from '../../api/attachmentApi';
import { OWNER_TYPE } from '../../constants/ownerType';
import { CONTEXT_KIND } from '../../constants/contextKind';
import { DRAFT_CHAT_ID } from '../../constants/storage';
import { generateUUID } from '../../utils/uuid';
import useAttachmentCount from '../common/useAttachmentCount';
import { attachmentDeleteErrorNotice, UPLOAD_ERROR_NOTICE } from './chatNotices';

/**
 * Вложения активного чата со стороны ChatWindow: счётчик для бейджа, сигнал на
 * перечитывание открытой панели и работа со скрепкой в композере.
 *
 * Панель вложений (AttachmentPanel) грузит список сама и один раз на чат — о
 * файле, приложённом или отменённом из композера, она узнаёт только по
 * `refreshSignal`.
 *
 * @param {object}   p
 * @param {string}   p.activeChatId
 * @param {Function} p.setChats
 * @param {Function} p.selectChat
 * @param {Function} p.stageContextItem    (chatId, item) => void
 * @param {Function} p.unstageContextItem  (chatId, item) => void
 * @param {Function} p.moveDraft           (fromId, toId) => void
 * @param {Function} p.notify              (дескриптор) => void — см. chatNotices
 */
export default function useChatAttachments({
  activeChatId,
  setChats,
  selectChat,
  stageContextItem,
  unstageContextItem,
  moveDraft,
  notify,
}) {
  // Счётчик для бейджа. У черновика чата на бэке ещё нет — считать нечего.
  const [attachCount, setAttachCount] = useAttachmentCount(
    OWNER_TYPE.CHAT,
    activeChatId && activeChatId !== DRAFT_CHAT_ID ? activeChatId : null,
  );
  // Bump → перечитать список в открытой панели вложений.
  const [refreshSignal, setRefreshSignal] = useState(0);

  // Прикрепить файл из композера. Файл сразу становится вложением чата (модель
  // сможет прочитать его инструментом в любой момент), но вдобавок откладывается
  // чипом к следующему сообщению — чтобы модель узнала о нём, не спрашивая.
  // Правую панель при этом не раскрываем: чип над полем ввода — более точная
  // обратная связь, чем открывшийся список всех вложений чата.
  //
  // В черновике настоящий conversationId рождается прямо здесь — как и при отправке
  // первого сообщения. Заводить чат отдельным запросом не нужно: строку в chat_topic
  // создаёт сама загрузка вложения (ChatTopicService), в одной транзакции с файлом.
  const attachFile = useCallback(
    async (file) => {
      if (!activeChatId || !file) return;
      const isDraft = activeChatId === DRAFT_CHAT_ID;
      const conversationId = isDraft ? generateUUID() : activeChatId;
      try {
        const uploaded = await attachmentApi.upload(OWNER_TYPE.CHAT, conversationId, file);
        if (isDraft) {
          setChats((prev) => {
            const found = prev.find((c) => c.id === DRAFT_CHAT_ID);
            if (!found) return prev;
            return [
              // messages: [] — не null: иначе useChatMessages пойдёт грузить историю,
              // которой у только что заведённого чата ещё нет.
              { ...found, id: conversationId, draft: false, messages: found.messages || [] },
              ...prev.filter((c) => c.id !== DRAFT_CHAT_ID),
            ];
          });
          moveDraft(DRAFT_CHAT_ID, conversationId);
          selectChat(conversationId);
          // Счётчик бейджа не трогаем: у чата сменился id, и useAttachmentCount
          // перечитает его сам — иначе прибавка либо потеряется, либо задвоится.
        } else {
          setAttachCount((n) => n + 1);
        }
        stageContextItem(conversationId, {
          kind: CONTEXT_KIND.ATTACHMENT,
          ref: String(uploaded.id),
          label: uploaded.fileName,
        });
        setRefreshSignal((n) => n + 1);
      } catch (err) {
        console.error('Upload error:', err);
        notify(UPLOAD_ERROR_NOTICE);
      }
    },
    [activeChatId, setChats, selectChat, stageContextItem, moveDraft, setAttachCount, notify],
  );

  // Снять чип из композера. К этому моменту файл уже лежит вложением чата, и чип —
  // единственный его след на экране: оставить файл значит оставить незамеченным то,
  // что пользователь только что отменил. Поэтому «убрать чип» — это удалить вложение.
  const unstageContext = useCallback(
    async (item) => {
      unstageContextItem(activeChatId, item);
      if (item.kind !== CONTEXT_KIND.ATTACHMENT) return;
      try {
        const res = await attachmentApi.delete(item.ref);
        // Как и в панели вложений (useAttachments.remove): requestRaw не бросает
        // на !ok, а 404 — не отказ, файла и правда уже нет (удалён в другой
        // вкладке), и счётчик с панелью надо привести в порядок так же, как при
        // успехе. Реальный отказ — единственная ветка, где ничего не трогаем.
        if (!res.ok && res.status !== 404) {
          notify(attachmentDeleteErrorNotice(res.status));
          return;
        }
        setAttachCount((n) => Math.max(0, n - 1));
        setRefreshSignal((n) => n + 1);
      } catch (err) {
        console.error('Ошибка удаления вложения:', err);
        notify(attachmentDeleteErrorNotice('network'));
      }
    },
    [activeChatId, unstageContextItem, setAttachCount, notify],
  );

  // Файл удалили из панели вложений, а он всё ещё отложен чипом к следующему сообщению.
  // Снимаем чип: иначе отправка упала бы на 404 из-за ссылки на то, чего уже нет.
  const handleAttachmentDeleted = useCallback(
    (id) => unstageContextItem(activeChatId, { kind: CONTEXT_KIND.ATTACHMENT, ref: String(id) }),
    [activeChatId, unstageContextItem],
  );

  return { attachCount, setAttachCount, refreshSignal, attachFile, unstageContext, handleAttachmentDeleted };
}
