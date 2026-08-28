import { useEffect, useEffectEvent, useRef, useState } from 'react';
import i18n from '@/i18n/index';
import { openChatEventStream } from '@/api/chatEvents';
import { applyChatEvent } from './chatEventReducer';
import { DRAFT_CHAT_ID } from '@/constants/storage';
import { CHAT_EVENT } from '@/constants/chatEventTypes';
import { collectChangeRefs } from '../messages/toolMeta';
import { fetchRunState, IDLE_RUN_STATE } from './activeRun';

// Мутации ОДНОГО прогона по загруженной истории: вызовы инструментов лежат у пузырей
// ассистента с его runId (см. transformPage). Дальше последней страницы не смотрим —
// начало очень длинного прогона могло уехать в ещё не догруженные страницы, и там
// найденное уже не окупает лишних запросов.
const runChangeRefs = (msgs, runId) => {
  const calls = [];
  for (const m of msgs || []) {
    if (m.toolCallsRunId === runId) calls.push(...(m.toolCalls || []));
  }
  return collectChangeRefs(calls);
};

/**
 * Подписка на поток событий активного чата: стриминг ответа + синхронизация между
 * вкладками. Подключаемся ТОЛЬКО когда история уже загружена (messages — массив),
 * чтобы события легли поверх неё, а не были затёрты последующей загрузкой из БД.
 * При обрыве поток сам переподключается и дозагружает пропущенное (см. chatEvents).
 *
 * Геттеры и сеттеры (getChats, isLocalClientId, setChats) стабильны и не входят
 * в зависимости эффекта — пересоздавать подписку на каждый чанк нельзя.
 * Подписи для редьюсера берём у i18n напрямую: t() из хука менялся бы со сменой
 * языка, а поток из-за неё переподключаться не должен.
 *
 * @param {object}   p
 * @param {string}   p.activeChatId
 * @param {boolean}  p.activeMessagesReady  загружена ли история активного чата
 * @param {Function} p.getChats             () => чаты: свежий снимок списка
 * @param {Function} p.isLocalClientId      (clientMsgId) => bool: своё сообщение (гасим эхо)
 * @param {Function} p.setChats
 * @param {Function} p.onChatDeleted        (chatId) => void — внешнее удаление чата
 * @param {Function} p.onRunSettled         (chatId) => void — RUN_DONE/STOPPED/ERROR, а также
 *                                           когда прогон обнаружился завершённым без нас
 * @param {Function} p.reloadMessages       (chatId) => Promise<Array|undefined> — перезагрузка
 *                                           истории; вернувшиеся пузыри нужны для догоняющей
 *                                           инвалидации кэшей (см. settleStaleRun)
 * @param {Function} [p.onDocChanged]       (refs) => void — успешные doc-мутации инструментов
 *                                           (createDocument/updateDocument/...) из ОДНОГО TOOL_CALLS
 *                                           события (или из истории того же прогона, если событие
 *                                           прошло мимо), refs — непустой массив из getDocChangeRef.
 *                                           Один вызов на событие (а не один на tool call): несколько
 *                                           setState подряд в одном тике React 18 схлопнёт до
 *                                           последнего, так что раздельные вызовы потеряли бы все
 *                                           мутации прогона, кроме последней, — например, при
 *                                           создании нескольких документов в одном ответе ассистента.
 * @param {Function} [p.onFileChanged]      (refs) => void — то же для file-мутаций
 *                                           (createFile/editFile/runScript), refs из getFileChangeRefs;
 *                                           один tool call может дать несколько refs — runScript
 *                                           применяет пачку правок за вызов.
 * @param {Function} [p.onRepoChanged]      () => void — git-команда пользователя сдвинула рабочее
 *                                           дерево целиком; какие пути — не знает никто, кроме git,
 *                                           поэтому аргументов нет.
 */
export default function useChatEventStream({
  activeChatId,
  activeMessagesReady,
  getChats,
  isLocalClientId,
  setChats,
  onChatDeleted,
  onRunSettled,
  reloadMessages,
  onDocChanged,
  onFileChanged,
  onRepoChanged,
}) {
  // Курсор последнего виденного seq по КАЖДОМУ чату. Живёт всё время, пока смонтирован
  // компонент (переживает переключения чатов), но не переживает перезагрузку страницы —
  // то есть ровно тогда, когда нужно продолжить, а не реплеить заново. Без него каждое
  // возвращение в чат открывало бы поток с fromSeq=0, хаб реплеил бы весь текущий прогон
  // с начала, а редьюсер дописал бы этот реплей поверх уже собранного пузыря — ответ
  // задваивался бы (и выглядел бы как «данные другого чата», когда вопрос в чатах похож).
  const seqByChatRef = useRef(new Map());

  // Счётчик переподписок. Сдвигается, когда обнаружено расхождение с бэком (см.
  // settleStaleRun): поток нужно открыть заново — уже с fromSeq=0, потому что курсор
  // прошлого прогона к новому хабу отношения не имеет.
  const [resyncTick, setResyncTick] = useState(0);

  // Колбэки мутаций зовём только из эффекта, поэтому useEffectEvent: в зависимости они
  // не входят (подписку нельзя пересоздавать на каждый чанк), но вызов обязан попадать
  // в свежее замыкание. Замороженное на момент подписки сбрасывало бы кэши файлов по
  // проекту, выбранному в чате ТОГДА, — то есть по чужому репозиторию, если проект чата
  // с тех пор сменили (см. handleFileChanged в ChatWindow).
  const fireDocChanged = useEffectEvent((refs) => onDocChanged?.(refs));
  const fireFileChanged = useEffectEvent((refs) => onFileChanged?.(refs));
  const fireRepoChanged = useEffectEvent(() => onRepoChanged?.());

  useEffect(() => {
    const chatId = activeChatId;
    if (!chatId || chatId === DRAFT_CHAT_ID) return undefined;
    const chat = getChats().find((c) => c.id === chatId);
    if (!chat || !Array.isArray(chat.messages) || chat.notFound || chat.loadError) return undefined;

    let cancelled = false;
    // Закрывалка потока. Объявлена здесь, до settleStaleRun, который её зовёт: держать
    // её только в const ниже значило бы, что любой будущий СИНХРОННЫЙ вызов оттуда
    // падает по TDZ — сейчас спасает лишь то, что все пути идут через колбэк промиса,
    // а ESLint такую ловушку не видит. До подписки закрывать нечего — no-op.
    let closeStream = () => {};

    // Один вызов колбэка со ВСЕМ списком, а не по одному на tool call — см. JSDoc выше.
    const fireChangeRefs = ({ docRefs, fileRefs }) => {
      if (docRefs.length > 0) fireDocChanged(docRefs);
      if (fileRefs.length > 0) fireFileChanged(fileRefs);
    };

    // Прогон, который UI считает идущим, на бэке уже не тот (завершился или сменился
    // новым): показываем ответ из БД, разблокируем ввод и переоткрываем поток с нуля.
    //
    // Порядок здесь — не вкусовщина. Сначала закрываем текущий поток: пока он открыт, он
    // продолжает двигать курсор seq (onSeq) и писать события в чат, то есть и вернул бы
    // выброшенный курсор, и попал бы под затирающую перезагрузку истории. Потом ждём саму
    // перезагрузку и только после неё просим переподписку: поток подключается ТОЛЬКО
    // поверх загруженной истории (см. шапку хука) — иначе реплей лёг бы в старые messages,
    // а пришедшая следом страница из БД его затёрла.
    const settleStaleRun = (staleRunId) => {
      closeStream();
      seqByChatRef.current.delete(chatId);
      // runKind снимаем вместе с runId: занятость — это они вдвоём, и оставленный вид
      // операции пережил бы прогон, держа Stop выключенным во всех следующих генерациях
      // этого чата.
      setChats((prev) => prev.map((c) => (c.id === chatId ? { ...c, ...IDLE_RUN_STATE } : c)));
      onRunSettled(chatId);
      reloadMessages(chatId).then((msgs) => {
        setResyncTick((n) => n + 1);
        // Вместе с событиями прогона мимо прошли и его doc/file-мутации, а кэши базы знаний
        // и файлов ждут именно их. Достаём те же metas из перезагруженной истории — она и
        // так грузится, отдельного запроса не нужно. Инвалидируем независимо от того, ушёл
        // ли пользователь дальше (cancelled): кэши общие для всего приложения.
        fireChangeRefs(runChangeRefs(msgs, staleRunId));
      });
    };

    // Идёт ли ещё прогон, который показывает UI. Спрашиваем бэк и сверяем с runId чата
    // на момент ОТВЕТА (за время запроса поток мог сам закрыть прогон).
    const checkRunAlive = () =>
      fetchRunState(chatId)
        .then((state) => {
          if (cancelled) return;
          const cur = getChats().find((c) => c.id === chatId);
          if (!cur?.runId) return; // поток уже закрыл прогон сам — сверять нечего
          if (state.runId === cur.runId) return; // прогон жив — поток догонит пропущенное сам
          settleStaleRun(cur.runId);
        })
        .catch(() => {});

    const ctx = {
      isLocal: isLocalClientId,
      stoppedLabel: i18n.t('chat:window.stopped'),
      errorLabel: i18n.t('chat:window.genericError'),
      interruptedNote: `\n\n_**${i18n.t('chat:message.interrupted')}**_`,
      compactingLabel: `_${i18n.t('chat:compact.running')}_`,
      // Итог сжатия подписи здесь не получает: его рисует плашка (CompactNotice) — ровно та
      // же, что приезжает из истории после перезагрузки, и переводит она себя сама.
      compactErrorLabel: i18n.t('chat:compact.error'),
    };
    closeStream = openChatEventStream(chatId, {
      fromSeq: seqByChatRef.current.get(chatId) || 0,
      onSeq: (seq) => seqByChatRef.current.set(chatId, seq),
      onEvent: (ev) => {
        if (ev.type === 'CHAT_DELETED') {
          seqByChatRef.current.delete(chatId);
          onChatDeleted(chatId);
          return;
        }
        setChats((prev) => prev.map((c) => (c.id === chatId ? applyChatEvent(c, ev, ctx) : c)));
        // Итоговые metas прогона: resultMeta появляется только здесь (см. toolMeta.js), не в
        // живом TOOL_CALL — поэтому детектируем doc/file-мутации на этом событии.
        if (ev.type === CHAT_EVENT.TOOL_CALLS) {
          fireChangeRefs(collectChangeRefs(ev.payload?.toolCalls));
        }
        // Git-команда сдвинула рабочее дерево — и для этой вкладки тоже, даже
        // если запускали её в соседней: ветка, список изменений и содержимое
        // открытого файла у них общие. Какие пути поменялись, знает только git,
        // поэтому сигнал общий, как после команды из панели «Файлы».
        if (ev.type === CHAT_EVENT.GIT_COMMAND) {
          fireRepoChanged();
        }
        // Сжатие контекста завершилось — хаб закрыл ту же заявку на чат, что и у прогона
        // (см. ConversationSlots.claim), поэтому и курсор сбрасываем так же.
        if (ev.type === CHAT_EVENT.COMPACT_DONE || ev.type === CHAT_EVENT.COMPACT_ERROR) {
          seqByChatRef.current.delete(chatId);
        }
        if (ev.type === 'RUN_DONE' || ev.type === 'RUN_STOPPED' || ev.type === 'RUN_ERROR') {
          // Прогон завершён: хаб очистит свой лог, а следующий прогон в этом чате начнёт
          // seq заново (в т.ч. с нового хаба после выгрузки простаивающего). Сбрасываем
          // курсор, чтобы переподписка снова сделала полный реплей нового прогона.
          seqByChatRef.current.delete(chatId);
          onRunSettled(chatId);
        }
      },
      onReconnect: () => {
        // Соединение восстановилось. Жив ли прогон на самом деле? Если ДА —
        // переподключившийся поток сам догонит пропущенное (fromSeq = курсор чата) и
        // допишет в уже собранный пузырь; трогать историю нельзя, иначе перезагрузка из
        // БД обрезала бы начало ответа. Если НЕТ (бэк перезапустился / прогон завершился,
        // пока рвалось соединение) — показываем ответ из БД и разблокируем ввод.
        if (getChats().find((c) => c.id === chatId)?.runId) checkRunAlive();
      },
    });

    // Вход в чат, который UI считает генерирующим. Пока чат не был активным, потока у него
    // не было: прогон мог завершиться без нас, и реплей этого уже не покажет — хаб чистит
    // лог событий в конце прогона (ConversationHub#endRun). Без этой сверки чат навсегда
    // оставался бы с недописанным пузырём и заблокированным вводом, до перезагрузки
    // страницы. Спрашиваем ПОСЛЕ подписки: пока идёт запрос, события уже не теряются.
    if (chat.runId) checkRunAlive();

    return () => {
      cancelled = true;
      closeStream();
    };
  }, [activeChatId, activeMessagesReady, resyncTick, onRunSettled, onChatDeleted, reloadMessages]); // eslint-disable-line react-hooks/exhaustive-deps
}
