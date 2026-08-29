import { useCallback, useRef, useState } from 'react';
// Перевод вне рендера берём у самого i18n, а не у t() из хука: колбэки прогона
// не должны пересоздаваться на смену языка, а зеркалить t в рефе — не за чем.
import i18n from '@/i18n/index';
import chatApi from '@/api/chatApi';
import { SENDER } from '@/constants/messageSender';
import { RETRY_MODE } from '@/constants/retryMode';
import { RUN_KIND } from '@/constants/runKind';
import { generateUUID } from '@/utils/uuid';
import { nextMessageId } from '../messages/messageId';
import { setLastModel, setLastMode, setLastProject } from './lastChoiceStore';
import {
  RUN_BUSY_NOTICE,
  QUEUE_ERROR_NOTICE,
  RETRY_UNAVAILABLE_NOTICE,
  COMPACT_EMPTY_NOTICE,
  COMPACT_START_ERROR_NOTICE,
} from './chatNotices';
import { fetchRunState, IDLE_RUN_STATE } from './activeRun';

/**
 * Запуск прогона на бэке: обычный вопрос, сообщение в очередь идущего прогона и сжатие
 * контекста. Всё, что здесь есть, — обращение к API и разбор исхода; что именно отправлять
 * (черновик, команда, повтор, выбранные модель и проект) решает useChatRun.
 *
 * Здесь же живут clientMsgId-ы этой вкладки: заводит их отправка, снимает откат
 * оптимистичного пузыря, а читает их редьюсер событий, отличая своё эхо от чужого.
 *
 * @param {object}   p
 * @param {Function} p.getChats      () => чаты (свежий снимок)
 * @param {Function} p.patchChat     (id, patch) => void
 * @param {Function} p.patchMessages (id, fn) => void
 * @param {Function} p.notify        (дескриптор) => void — см. chatNotices
 */
export default function useRunStarter({ getChats, patchChat, patchMessages, notify }) {
  // chatId, для которого POST /runs уже отправлен, но runId ещё не получен.
  // Закрывает окно между кликом «отправить» и ответом сервера: ввод блокируется
  // сразу, а не с приходом runId.
  const [pendingRunChatId, setPendingRunChatId] = useState(null);

  // clientMsgId-ы сообщений, отправленных ИЗ ЭТОЙ вкладки. Нужны, чтобы не задвоить
  // свой оптимистично показанный пузырь, получив его же эхом из потока событий.
  const localClientIdsRef = useRef(new Set());
  const isLocalClientId = useCallback((id) => localClientIdsRef.current.has(id), []);

  const trackLocalId = useCallback((id) => {
    localClientIdsRef.current.add(id);
  }, []);

  // Идёт ли в чате прогон прямо сейчас. Локального runId недостаточно: он мог не успеть
  // приехать потоком, поэтому переспрашиваем бэк. Нужно там, где сбой запроса ещё не
  // означает, что генерация не началась (см. runConversation).
  const hasActiveRun = useCallback(
    async (conversationId) => {
      if (getChats().find((c) => c.id === conversationId)?.runId) return true;
      try {
        return !!(await fetchRunState(conversationId)).runId;
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
            runKind: RUN_KIND.GENERATION,
            // Якорь таймера над полем ввода. RUN_STARTED из потока ставит его же, если опередил.
            runStartedAt: c.runStartedAt ?? Date.now(),
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
          ...IDLE_RUN_STATE,
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

  // Отправка в очередь идущего прогона: пользователь пишет, не дожидаясь ответа.
  // Возвращает, что делать дальше:
  //   'queued' — принято, доставку покажет событие USER_MESSAGE;
  //   'run'    — прогон кончился, пока набирали (409): отправляем обычным путём;
  //   'failed' — не приняли; под пузырём появилась ошибка с «Повторить».
  // Ввод при этом НЕ блокируем (setPendingRunChatId): чат и так занят прогоном, а
  // блокировка отняла бы у пользователя ровно ту возможность, ради которой всё и сделано.
  const queueMessage = useCallback(
    async (conversationId, runId, send) => {
      const { text, clientMsgId, contextItems, model, mode, project } = send;
      try {
        await chatApi.queueMessage(conversationId, runId, text, {
          model,
          mode,
          project,
          clientMsgId,
          contextItems,
        });
        return 'queued';
      } catch (error) {
        if (error?.status === 409) return 'run';
        console.error('Failed to queue message:', error);
        // «Ожидающий» пузырь обещал бы доставку, которой не будет, — снимаем. Текст и вложения
        // при этом не теряются: черновик этого чата ещё не очищен (см. sendMessage), и
        // вызывающий возвращает его в поле ввода. clientMsgId из локальных НЕ убираем: запрос
        // мог не удаться уже после того, как бэк принял сообщение (обрыв ответа), и тогда
        // доставка придёт событием и заведёт пузырь заново — по этому же id.
        patchMessages(conversationId, (msgs) => msgs.filter((m) => m.clientMsgId !== clientMsgId));
        notify(QUEUE_ERROR_NOTICE);
        return 'failed';
      }
    },
    [patchMessages, notify],
  );

  // Сжатие контекста по команде `/compact`. Сообщение сохраняется на бэке как обычная
  // реплика (остаётся видно в истории, как и любой вопрос — только не участвует в самом
  // сжатии), поэтому здесь тот же оптимистичный пузырь, что и у sendMessage: клиент не
  // ждёт эха, чтобы показать, что команда отправлена. Плашку «сжимаю…» заводит отдельное
  // событие COMPACT_STARTED, одинаково во всех вкладках.
  const compactChat = useCallback(
    async (conversationId, text, instructions) => {
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      patchChat(conversationId, (c) => ({
        messages: [
          ...(c.messages || []),
          { mid: nextMessageId(), text, sender: SENDER.USER, clientMsgId, timestamp: new Date().toISOString() },
        ],
      }));
      setPendingRunChatId(conversationId);
      try {
        const res = await chatApi.compact(conversationId, text, instructions, clientMsgId);
        const runId = res?.runId;
        // id сохранённой команды — тот же приём, что и у обычного вопроса (см. runConversation).
        const dbId = Number(res?.messageId);
        const patchedId = Number.isFinite(dbId) ? dbId : null;
        if (runId) {
          patchChat(conversationId, (c) => ({
            runId,
            runKind: RUN_KIND.OPERATION,
            messages: patchedId
              ? (c.messages || []).map((m) => (m.clientMsgId === clientMsgId ? { ...m, dbId: patchedId } : m))
              : c.messages,
          }));
        }
      } catch (error) {
        // 409/422 проверяются на бэке ДО сохранения команды — она точно не записалась,
        // откатываем оптимистичный пузырь.
        const removeBubble = () => {
          localClientIdsRef.current.delete(clientMsgId);
          patchMessages(conversationId, (msgs) => msgs.filter((m) => m.clientMsgId !== clientMsgId));
        };
        if (error?.status === 409) {
          removeBubble();
          notify(RUN_BUSY_NOTICE);
          return;
        }
        // 422 — сжимать нечего: живой контекст уже состоит из одной сводки.
        if (error?.status === 422) {
          removeBubble();
          notify(COMPACT_EMPTY_NOTICE);
          return;
        }
        console.error('Failed to compact:', error);
        // Запрос мог не удаться уже ПОСЛЕ того, как бэк сохранил команду и начал раунд
        // (обрыв ответа) — тогда откатывать пузырь нельзя, сжатие всё равно идёт.
        if (await hasActiveRun(conversationId)) return;
        removeBubble();
        notify(COMPACT_START_ERROR_NOTICE);
      } finally {
        setPendingRunChatId((cur) => (cur === conversationId ? null : cur));
      }
    },
    [patchChat, patchMessages, hasActiveRun, notify],
  );

  return { pendingRunChatId, isLocalClientId, trackLocalId, runConversation, queueMessage, compactChat };
}
