import { useCallback } from 'react';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';
import { SENDER } from '@/constants/messageSender';
import { RETRY_MODE } from '@/constants/retryMode';
import { generateUUID } from '@/utils/uuid';
import { nextMessageId } from '../messages/messageId';
import { getLastModel, getLastMode } from './lastChoiceStore';
import { chatLoadErrorNotice, RUN_BUSY_NOTICE, COMPACT_DRAFT_NOTICE } from './chatNotices';
import { parseChatCommand, CHAT_COMMAND } from './chatCommands';
import useRunStarter from './useRunStarter';

/**
 * Отправка сообщения, повтор после ошибки и остановка генерации.
 *
 * Ответ здесь НЕ стримится: отправка лишь запускает фоновый прогон (POST /runs)
 * и оптимистично показывает свой вопрос. Сам ответ (и эхо вопроса для других
 * вкладок) приезжает потоком событий — см. useChatEventStream.
 *
 * Сами обращения к API прогона и разбор их исходов живут в useRunStarter; здесь —
 * решение, что именно отправлять: команда чату или вопрос, черновик или готовый чат,
 * очередь идущего прогона или новый, с какими моделью, режимом и проектом.
 *
 * @param {object}   p
 * @param {string}   p.activeChatId
 * @param {Function} p.getChats      () => чаты (свежий снимок)
 * @param {Function} p.setChats
 * @param {Function} p.patchChat     (id, patch) => void
 * @param {Function} p.patchMessages (id, fn) => void
 * @param {Function} p.selectChat    (id, opts) => void
 * @param {Function} p.clearDraft    (id) => void — черновик композера отправлен
 * @param {Function} p.clearDraftText (id) => void — из черновика ушёл только текст, вложения
 *                                    остались (команда чату, а не вопрос)
 * @param {Function} p.restoreDraft  () => void — вернуть в поле ввода текст из черновика: поле
 *                                    стирает его на отправке, а отправка может и не состояться
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
  clearDraftText,
  restoreDraft,
  getStagedFor,
  modelConfig,
  modelOptions,
  modeOptions,
  projectOptions,
  defaultProjectId,
  notify,
}) {
  const { pendingRunChatId, isLocalClientId, trackLocalId, runConversation, queueMessage, compactChat } = useRunStarter(
    { getChats, patchChat, patchMessages, notify },
  );

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

      // Команда чату, а не вопрос модели: у неё свой эндпоинт и свой жизненный цикл,
      // хотя в историю она, как и вопрос, попадает (см. compactChat).
      const command = parseChatCommand(text);
      if (command?.name === CHAT_COMMAND.COMPACT) {
        // Очереди у сжатия нет: опустошает её терминальная обработка прогона, а у сжатия её не
        // будет. Поэтому команда во время ответа — отказ, и отказ ДО очистки черновика: поле
        // ввода уже стёрло текст на отправке, и вернуть его можно только оттуда.
        if (chatForSend?.runId) {
          notify(RUN_BUSY_NOTICE);
          restoreDraft?.();
          return;
        }
        // В ещё не начатом чате сжимать нечего — и заводить его ради команды незачем.
        if (activeChatId === DRAFT_CHAT_ID) {
          notify(COMPACT_DRAFT_NOTICE);
          return;
        }
        // Только текст: команда не уносит с собой отложенные вложения — они приложены
        // к вопросу, который пользователь ещё задаст, и переживают сжатие.
        clearDraftText(activeChatId);
        await compactChat(activeChatId, text, command.args);
        return;
      }

      // Черновик: настоящий conversationId (UUID) рождается именно сейчас.
      // Для обычного чата conversationId === activeChatId.
      const isDraft = activeChatId === DRAFT_CHAT_ID;
      const conversationId = isDraft ? generateUUID() : activeChatId;
      // clientMsgId — чтобы не задвоить свой пузырь, получив его эхом из /events.
      const clientMsgId = generateUUID();
      trackLocalId(clientMsgId);
      const modelForSend = resolveModelForSend(chatForSend);
      const modeForSend = resolveModeForSend(chatForSend);
      const projectForSend = resolveProjectForSend(chatForSend);
      // Отложенные вложения этого чата уходят с сообщением: бэк проверит ссылки
      // и запишет их в meta того же ряда (см. ContextItemService).
      const contextItems = getStagedFor(activeChatId);

      // В чате идёт прогон — сообщение встаёт в его очередь, а не ждёт конца ответа.
      // Прогон обязан быть известен по runId: без него очередь некуда адресовать (композер
      // на это окно и заблокирован, см. ChatWindow). Черновик сюда попасть не может — в нём
      // прогона нет по построению.
      const runIdForQueue = isDraft ? null : chatForSend?.runId || null;

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
            // Ряда истории у поставленного в очередь ещё нет — пузырь ждёт доставки.
            ...(runIdForQueue ? { queued: true } : {}),
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
      const send = {
        text,
        clientMsgId,
        contextItems,
        model: modelForSend,
        mode: modeForSend,
        project: projectForSend,
      };
      if (runIdForQueue) {
        // Черновик здесь чистим ПОСЛЕ ответа, а не до: у обычной отправки сбой оставляет за
        // собой пузырь с «Повторить» по тексту вопроса, а у очереди пузыря не остаётся — и
        // единственное, откуда можно вернуть набранное, это нетронутый черновик.
        const outcome = await queueMessage(conversationId, runIdForQueue, send);
        if (outcome === 'failed') {
          restoreDraft?.();
          return;
        }
        clearDraft(activeChatId);
        if (outcome === 'queued') return;
        // 'run' — прогон кончился, пока набирали: отправляем обычным путём. Пузырь уже
        // показан, снимаем с него «ожидает» — доставлять теперь нечего, это обычный вопрос.
        patchMessages(conversationId, (msgs) =>
          msgs.map((m) => {
            if (m.clientMsgId !== clientMsgId) return m;
            const { queued: _drop, ...rest } = m;
            return rest;
          }),
        );
      } else {
        // Сообщение ушло — черновик этого чата больше не нужен.
        clearDraft(activeChatId);
      }
      await runConversation(conversationId, send);
    },
    [
      activeChatId,
      getChats,
      setChats,
      selectChat,
      clearDraft,
      clearDraftText,
      restoreDraft,
      getStagedFor,
      resolveModelForSend,
      resolveModeForSend,
      resolveProjectForSend,
      runConversation,
      queueMessage,
      patchMessages,
      compactChat,
      trackLocalId,
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
      trackLocalId(clientMsgId);
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
      trackLocalId,
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
