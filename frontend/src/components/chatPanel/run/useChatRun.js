import { useCallback, useRef, useState } from 'react';
// Перевод вне рендера берём у самого i18n, а не у t() из хука: колбэки стриминга
// не должны пересоздаваться на смену языка, а зеркалить t в рефе — не за чем.
import i18n from '../../../i18n';
import chatApi from '../../../api/chatApi';
import { DRAFT_CHAT_ID } from '../../../constants/storage';
import { SENDER } from '../../../constants/messageSender';
import { RETRY_MODE } from '../../../constants/retryMode';
import { generateUUID } from '../../../utils/uuid';
import { nextMessageId } from '../messages/messageId';
import { getLastModel, setLastModel, getLastMode, setLastMode, setLastProject } from '../lastChoiceStore';
import { chatLoadErrorNotice, RUN_BUSY_NOTICE, RETRY_UNAVAILABLE_NOTICE } from '../chatNotices';

/**
 * Отправка сообщения, повтор после ошибки и остановка генерации.
 *
 * Ответ здесь НЕ стримится: отправка лишь запускает фоновый прогон (POST /runs)
 * и оптимистично показывает свой вопрос. Сам ответ (и эхо вопроса для других
 * вкладок) приезжает потоком событий — см. useChatEventStream.
 *
 * @param {object}   p
 * @param {string}   p.activeChatId
 * @param {Function} p.getChats      () => чаты (свежий снимок)
 * @param {Function} p.setChats
 * @param {Function} p.patchChat     (id, patch) => void
 * @param {Function} p.patchMessages (id, fn) => void
 * @param {Function} p.selectChat    (id, opts) => void
 * @param {Function} p.clearDraft    (id) => void — черновик композера отправлен
 * @param {Function} p.getStagedFor  (id) => отложенные к сообщению вложения
 * @param {object}   p.modelConfig
 * @param {Array}    p.modelOptions
 * @param {Array}    p.modeOptions
 * @param {Array}    p.projectOptions
 * @param {string}   p.defaultProjectId
 * @param {Function} p.notify        (дескриптор) => void — см. chatNotices
 */
export default function useChatRun({
  activeChatId,
  getChats,
  setChats,
  patchChat,
  patchMessages,
  selectChat,
  clearDraft,
  getStagedFor,
  modelConfig,
  modelOptions,
  modeOptions,
  projectOptions,
  defaultProjectId,
  notify,
}) {
  // chatId, для которого POST /runs уже отправлен, но runId ещё не получен.
  // Закрывает окно между кликом «отправить» и ответом сервера: ввод блокируется
  // сразу, а не с приходом runId.
  const [pendingRunChatId, setPendingRunChatId] = useState(null);

  // clientMsgId-ы сообщений, отправленных ИЗ ЭТОЙ вкладки. Нужны, чтобы не задвоить
  // свой оптимистично показанный пузырь, получив его же эхом из потока событий.
  const localClientIdsRef = useRef(new Set());
  const isLocalClientId = useCallback((id) => localClientIdsRef.current.has(id), []);

  // Модель для отправки — всегда явная: выбранная у чата → последняя → дефолтная.
  const resolveModelForSend = useCallback(
    (chat) => {
      const selected = chat?.model;
      if (selected && modelOptions.some((o) => o.id === selected)) return selected;
      const last = getLastModel();
      if (last && modelOptions.some((o) => o.id === last)) return last;
      return modelConfig?.defaultModel?.id || null;
    },
    [modelOptions, modelConfig],
  );

  // Режим для отправки: выбранный у чата → последний → без режима (''). Значение
  // валидируем по конфигу (режим мог исчезнуть).
  const resolveModeForSend = useCallback(
    (chat) => {
      const selected = chat?.mode;
      if (selected && modeOptions.some((o) => o.id === selected)) return selected;
      const last = getLastMode();
      if (last && modeOptions.some((o) => o.id === last)) return last;
      return '';
    },
    [modeOptions],
  );

  // Проект для отправки: выбранный у чата → дефолтный, если его убрали из конфига.
  // Ровно то же считает selectedProjectId в ChatWindow, и это обязано совпадать: на
  // экране стоит один проект, а прогон обязан уехать в него же. Через «последний
  // выбранный» здесь не идём — новым чатом он подставляется в makeDraft, то есть
  // виден в композере; молча взятый на отправке, он увёл бы прогон в репозиторий,
  // о котором на экране нет ни слова, и записал бы его чату (бэкенд считает
  // непустой project явным выбором пользователя).
  const resolveProjectForSend = useCallback(
    (chat) => {
      const selected = chat?.project;
      if (selected && projectOptions.some((o) => o.id === selected)) return selected;
      return defaultProjectId || null;
    },
    [projectOptions, defaultProjectId],
  );

  // Идёт ли в чате прогон прямо сейчас. Локального runId недостаточно: он мог не успеть
  // приехать потоком, поэтому переспрашиваем бэк. Нужно там, где сбой запроса ещё не
  // означает, что генерация не началась (см. runConversation).
  const hasActiveRun = useCallback(
    async (conversationId) => {
      if (getChats().find((c) => c.id === conversationId)?.runId) return true;
      try {
        const active = await chatApi.getActiveRun(conversationId);
        return !!active?.runId;
      } catch {
        return false;
      }
    },
    [getChats],
  );

  // Старт фонового прогона для уже показанного вопроса. Общий код для первой отправки
  // и для «Повторить»: бьёт POST /runs и обрабатывает исход — runId (идёт генерация),
  // 409 (занято), 422 (повторять нечего) или сбой запроса.
  //
  // retry: true — повтор упавшего прогона (RETRY_MODE.CONTINUE). Текста не передаём:
  // вопрос уже сохранён на бэке, ходом остаётся он же. Оптимистичного пузыря здесь нет
  // и clientMsgId не нужен — ошибочный пузырь снимет эхо USER_MESSAGE, одинаково во всех
  // вкладках. retryMid — пузырь, из которого нажали повтор: с него снимаем кнопку, если
  // бэк ответил «повторять уже нечего».
  const runConversation = useCallback(
    async (
      conversationId,
      { text = null, clientMsgId = null, model, mode, project, retry = false, retryMid = null, contextItems = [] },
    ) => {
      // Запоминаем как «последние» — новый чат стартует именно с них. Режим
      // запоминаем всегда, в т.ч. '' — это сознательный сброс к «без режима».
      if (model) setLastModel(model);
      setLastMode(mode);
      if (project) setLastProject(project);
      // Блокируем ввод сразу, не дожидаясь runId от сервера. Снимается в finally:
      // при успехе к этому моменту у чата уже стоит runId (isStreaming не мигает),
      // при 409/ошибке ввод разблокируется — отправку можно повторить.
      setPendingRunChatId(conversationId);
      try {
        const res = await chatApi.startRun(conversationId, text, {
          model,
          mode,
          project,
          clientMsgId,
          retry,
          contextItems,
        });
        const runId = res?.runId;
        // id сохранённого вопроса: проставляем оптимистичному пузырю как dbId — якорь для
        // поиска по чату (find-бар). Своё эхо USER_MESSAGE эта вкладка гасит по clientMsgId,
        // так что другого источника id у неё нет. На повторе оптимистичного пузыря нет —
        // там id уже стоит с первой отправки (или приедет эхом, которое ничем не гасится).
        const dbId = Number(res?.messageId);
        const patchedId = clientMsgId && Number.isFinite(dbId) ? dbId : null;
        // Помечаем чат активным прогоном → кнопка «остановить», блокировка ввода.
        // (RUN_STARTED из потока проставит то же самое, если опередит.)
        if (runId) {
          patchChat(conversationId, (c) => ({
            runId,
            messages: patchedId
              ? (c.messages || []).map((m) => (m.clientMsgId === clientMsgId ? { ...m, dbId: patchedId } : m))
              : c.messages,
          }));
        }
      } catch (error) {
        // Не наша заявка — генерация уже идёт (часто из другой вкладки). Откатываем
        // оптимистичный пузырь (если был) и сообщаем пользователю. Текущий прогон всё
        // равно «прилетит» потоком событий (RUN_STARTED) и покажет «остановить».
        if (error?.status === 409) {
          if (clientMsgId) {
            localClientIdsRef.current.delete(clientMsgId);
            patchMessages(conversationId, (msgs) => msgs.filter((m) => m.clientMsgId !== clientMsgId));
          }
          notify(RUN_BUSY_NOTICE);
          return;
        }
        console.error('Failed to start run:', error);
        // 422 — повторять уже нечего: чат ушёл вперёд (другая вкладка, гонка с событием).
        // Снимаем кнопку с этого пузыря: дальше диалог продолжается обычным сообщением.
        if (error?.status === 422) {
          patchMessages(conversationId, (msgs) =>
            msgs.map((m) => (m.mid === retryMid ? { ...m, retryMode: undefined } : m)),
          );
          notify(RETRY_UNAVAILABLE_NOTICE);
          return;
        }
        // Запрос не удался — но прогон мог всё-таки стартовать: POST дошёл, а ответ до нас
        // нет (обрыв, прокси, спящая вкладка). Тогда вопрос уже сохранён и генерация идёт,
        // а пузырь «ошибка + повторить» предложил бы отправить тот же вопрос второй раз.
        if (await hasActiveRun(conversationId)) return;
        // На повторе показывать нечего: пузырь с ошибкой и его кнопка никуда не делись —
        // состояние чата не изменилось, повтор можно нажать ещё раз.
        if (retry) return;
        // Прогон не идёт, но он мог успеть и стартовать, и завершиться. Снимаем гашение
        // своего эха: если события с вопросом и ответом всё-таки придут, USER_MESSAGE
        // опознает наш пузырь по тексту и срежет всё, что показано после него, — вместе
        // с этой ошибкой. Не придут — останется ошибка с повтором по тексту вопроса.
        localClientIdsRef.current.delete(clientMsgId);
        patchChat(conversationId, (c) => ({
          runId: null,
          messages: [
            ...(c.messages || []),
            {
              mid: nextMessageId(),
              text: i18n.t('chat:window.genericError'),
              sender: SENDER.AI,
              error: true,
              retryMode: RETRY_MODE.RESEND,
              retryText: text,
              retryContextItems: contextItems,
            },
          ],
        }));
      } finally {
        setPendingRunChatId((cur) => (cur === conversationId ? null : cur));
      }
    },
    [hasActiveRun, patchChat, patchMessages, notify],
  );

  const sendMessage = useCallback(
    async (text) => {
      if (!activeChatId) return;

      // Если активный чат недоступен (не найден / ошибка загрузки) —
      // не отправляем запрос, а показываем модалку.
      const chatForSend = getChats().find((c) => c.id === activeChatId);
      if (chatForSend?.notFound || chatForSend?.loadError) {
        notify(chatLoadErrorNotice({ notFound: !!chatForSend.notFound, status: chatForSend.loadError }));
        return;
      }

      // Черновик: настоящий conversationId (UUID) рождается именно сейчас.
      // Для обычного чата conversationId === activeChatId.
      const isDraft = activeChatId === DRAFT_CHAT_ID;
      const conversationId = isDraft ? generateUUID() : activeChatId;
      // clientMsgId — чтобы не задвоить свой пузырь, получив его эхом из /events.
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      const modelForSend = resolveModelForSend(chatForSend);
      const modeForSend = resolveModeForSend(chatForSend);
      const projectForSend = resolveProjectForSend(chatForSend);
      // Отложенные вложения этого чата уходят с сообщением: бэк проверит ссылки
      // и запишет их в meta того же ряда (см. ContextItemService).
      const contextItems = getStagedFor(activeChatId);

      // Оптимистично: промоутим черновик и показываем пузырь пользователя.
      // AI-пузырь не добавляем — его создаст событие RUN_STARTED. Не patchChat:
      // у чата меняется id и он поднимается наверх списка, то есть меняется сам
      // список, а не один его элемент.
      setChats((prev) => {
        const found = prev.find((c) => c.id === activeChatId);
        if (!found) return prev;
        const newMessages = [
          ...(found.messages || []),
          {
            mid: nextMessageId(),
            text,
            sender: SENDER.USER,
            clientMsgId,
            contextItems,
            timestamp: new Date().toISOString(),
          },
        ];
        const updatedChat = {
          ...found,
          id: conversationId,
          draft: false,
          model: modelForSend ?? found.model ?? null,
          mode: modeForSend || found.mode || null,
          project: projectForSend ?? found.project ?? null,
          messages: newMessages,
        };
        const otherChats = prev.filter((c) => c.id !== activeChatId);
        return [updatedChat, ...otherChats];
      });

      // Поднимаем реальный id в URL/навигацию: '/new' → '/<uuid>'.
      if (isDraft) {
        selectChat(conversationId);
      }
      // Сообщение ушло — черновик этого чата больше не нужен.
      clearDraft(activeChatId);

      await runConversation(conversationId, {
        text,
        clientMsgId,
        contextItems,
        model: modelForSend,
        mode: modeForSend,
        project: projectForSend,
      });
    },
    [
      activeChatId,
      getChats,
      setChats,
      selectChat,
      clearDraft,
      getStagedFor,
      resolveModelForSend,
      resolveModeForSend,
      resolveProjectForSend,
      runConversation,
      notify,
    ],
  );

  // Повтор после ошибки. Что именно значит «Повторить», решено ещё в момент ошибки
  // (constants/retryMode.js) — только там известно, доехал ли вопрос до бэка:
  //   • CONTINUE — вопрос сохранён, а ответа нет ни одного: прогон запускается поверх той
  //     же истории, второго USER-сообщения не появляется. Ошибочный пузырь снимет эхо
  //     USER_MESSAGE — сразу во всех вкладках, поэтому локально его не трогаем.
  //   • RESEND — сбой самого POST /runs: вопрос не сохранён, отправляем его текст заново.
  //     Пузырь пользователя уже на месте — новый не добавляем, эхо гасится по clientMsgId.
  // Пузырей без retryMode здесь не бывает: у них нет и кнопки (см. MessageList).
  // Пузырь ищем по mid, а не по индексу в массиве: догрузка старых страниц
  // добавляет сообщения В НАЧАЛО списка, и индекс из замыкания рендера успел бы
  // устареть — фильтр по индексу снял бы не тот пузырь.
  const retryMessage = useCallback(
    (mid) => {
      const chat = getChats().find((c) => c.id === activeChatId);
      // Во время генерации/ожидания старта В ЭТОМ чате повтор недоступен;
      // pending в другом чате повтору здесь не мешает (как и в isStreaming).
      if (!chat || chat.runId || pendingRunChatId === activeChatId) return;
      const target = (chat.messages || []).find((m) => m.mid === mid);
      if (!target || target.sender !== SENDER.AI || !target.error) return;
      const model = resolveModelForSend(chat);
      const mode = resolveModeForSend(chat);
      const project = resolveProjectForSend(chat);

      if (target.retryMode === RETRY_MODE.CONTINUE) {
        runConversation(activeChatId, { retry: true, retryMid: mid, model, mode, project });
        return;
      }
      if (target.retryMode !== RETRY_MODE.RESEND) return;
      const text = target.retryText;
      if (!text || !text.trim()) return;
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      // Снимаем ошибочный AI-пузырь, чтобы не копить ошибки.
      patchMessages(activeChatId, (msgs) => msgs.filter((m) => m.mid !== mid));
      runConversation(activeChatId, {
        text,
        clientMsgId,
        model,
        mode,
        project,
        contextItems: target.retryContextItems || [],
      });
    },
    [
      activeChatId,
      getChats,
      pendingRunChatId,
      patchMessages,
      resolveModelForSend,
      resolveModeForSend,
      resolveProjectForSend,
      runConversation,
    ],
  );

  const stopGeneration = useCallback(() => {
    const chat = getChats().find((c) => c.id === activeChatId);
    if (chat?.runId) {
      // Явный сигнал на бэк. Пузырь обновит событие RUN_STOPPED (во всех вкладках).
      chatApi.stopRun(chat.id, chat.runId);
    }
  }, [activeChatId, getChats]);

  return { pendingRunChatId, isLocalClientId, sendMessage, retryMessage, stopGeneration };
}
