// ─── In-chat search (Ctrl+F) ────────────────────────────────────────────────
// Find-бар для одного открытого чата: ищет совпадения на бэке (история
// пагинирована, во фронте может быть загружен лишь хвост), затем даёт
// навигацию prev/next по найденным сообщениям. Если совпадение лежит в ещё
// не загруженной (более старой) странице — молча догружает её же самым
// хуком useChatMessages.loadOlderMessages, пока сообщение не появится в DOM
// (см. MessageList: именно оно делает финальный скролл и подсветку по mid).

import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';

const DEBOUNCE_MS = 250;
// Стабильный «нет совпадений»: сброс делается прямо в рендере, и новый литерал
// каждый раз давал бы лишний ре-рендер вместо тихого выхода из setState.
const NO_MATCHES = [];
// Safety cap на число страниц, которые догружаем в поисках одного совпадения —
// на случай рассинхронизации курсора не крутим цикл бесконечно.
const MAX_LOAD_STEPS = 50;

/**
 * Пузырь (его mid), которому соответствует активное совпадение поиска.
 *
 * Обычный случай — пузырь с таким dbId уже загружен. Но сообщения, появившиеся
 * в текущей сессии (отправка/стриминг), в стейте без dbId: их id знает только
 * бэкенд. Для них сопоставляем по порядку: и хиты бэкенда, и пузыри хронологичны,
 * поэтому k-й «свежий» хит (id новее самого нового загруженного dbId) — это k-й
 * пузырь без dbId, содержащий запрос.
 *
 * @returns {*} mid пузыря или null (совпадение в ещё не догруженной странице)
 */
export function resolveActiveMatchMid({ messages, matches, activeMatch, query }) {
  if (!activeMatch || !Array.isArray(messages) || messages.length === 0) return null;

  const direct = messages.find((m) => m.dbId === activeMatch.id);
  if (direct) return direct.mid;

  let newestLoadedDbId = null;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].dbId != null) {
      newestLoadedDbId = messages[i].dbId;
      break;
    }
  }
  // Старее самого нового загруженного, но не найден — лежит в незагруженной
  // странице; догрузку страниц ведёт эффект в useInChatSearch.
  if (newestLoadedDbId != null && activeMatch.id < newestLoadedDbId) return null;

  const q = (query || '').trim().toLowerCase();
  if (!q) return null;
  const freshHits = matches.filter((h) => newestLoadedDbId == null || h.id > newestLoadedDbId);
  const k = freshHits.findIndex((h) => h.id === activeMatch.id);
  if (k < 0) return null;
  const freshBubbles = messages.filter((m) => m.dbId == null && (m.text || '').toLowerCase().includes(q));
  return freshBubbles[k]?.mid ?? null;
}

/**
 * Индекс совпадения на сообщении `msg`; -1, если такого среди совпадений нет.
 * Сравниваем по числу: из адреса id приходит строкой, у хита он числовой.
 */
export function indexOfMessage(matches, msg) {
  const id = Number(msg);
  if (!msg || !Number.isFinite(id)) return -1;
  return matches.findIndex((m) => m.id === id);
}

/**
 * @param find         запрос из адреса ('' — в чат пришли не из поиска)
 * @param msg          id сообщения из адреса: с него начать вместо самого
 *                     свежего совпадения ('' — пришли не по ссылке на сообщение)
 * @param onFindChange записать запрос в адрес
 */
export default function useInChatSearch({
  activeChatId,
  getChats,
  loadOlderMessages,
  messages,
  find,
  msg = '',
  onFindChange,
}) {
  const [open, setOpen] = useState(!!find);
  const [query, setQuery] = useState(find || '');
  const [matches, setMatches] = useState(NO_MATCHES); // [{ id, createdAt }] хронологически (ASC)
  const [activeIndex, setActiveIndex] = useState(-1);
  const [searching, setSearching] = useState(false);
  const [navigating, setNavigating] = useState(false);

  // Какое сообщение из адреса ещё не применено. Применяем не сразу: список
  // совпадений серверный и приезжает после запроса, а до него садиться некуда.
  const [pendingMsg, setPendingMsg] = useState(msg);
  // Счётчик шагов стрелками. Ответ уже отправленного запроса приходит позже
  // шага, и сравнение счётчика — единственный способ узнать, что человек за это
  // время выбрал совпадение сам.
  const stepsRef = useRef(0);

  const debounceRef = useRef(null);
  const abortRef = useRef(null);
  const navSeqRef = useRef(0); // гасит устаревшую навигацию (чат/индекс сменились по пути)

  const resetResults = useCallback(() => {
    clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    setMatches(NO_MATCHES);
    setActiveIndex(-1);
    setSearching(false);
    setPendingMsg('');
  }, []);

  const close = useCallback(() => {
    resetResults();
    setOpen(false);
    setQuery('');
    onFindChange?.('');
  }, [resetResults, onFindChange]);

  const openBar = useCallback(() => setOpen(true), []);

  // Ссылку на сообщение отрабатываем в момент ответа поиска, а не эффектом
  // поверх готового списка: иначе остаётся кадр, где активно самое свежее
  // совпадение — лента успевала бы прокрутиться к нему и уехать обратно.
  const runSearch = useCallback((chatId, q, target) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const steps = stepsRef.current;
    setSearching(true);
    chatApi
      .searchMessages(chatId, q, controller.signal)
      .then((data) => {
        const list = Array.isArray(data) ? data : [];
        setMatches(list);
        // Сообщения из адреса среди совпадений может не быть: чат успели
        // почистить, запрос в баре — не тот, с которым пришли, или человек уже
        // ушёл стрелкой. Тогда, как и без ссылки, встаём на самое свежее.
        const at = stepsRef.current === steps ? indexOfMessage(list, target) : -1;
        setActiveIndex(at >= 0 ? at : list.length ? list.length - 1 : -1);
        setPendingMsg('');
        setSearching(false);
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          setMatches([]);
          setActiveIndex(-1);
          setSearching(false);
          // Сообщение из адреса относилось к этому запросу: отказ его не
          // откладывает на следующий, набранный в баре, — тот садится на самое
          // свежее совпадение, как любой набранный запрос.
          setPendingMsg('');
        }
      });
  }, []);

  // Пустой запрос — показывать нечего. Чистим в рендере, а не эффектом: иначе
  // остаётся кадр, где счётчик совпадений относится к уже стёртому запросу.
  const trimmedQuery = query.trim();
  const [prevQuery, setPrevQuery] = useState(trimmedQuery);
  if (prevQuery !== trimmedQuery) {
    setPrevQuery(trimmedQuery);
    if (!trimmedQuery) {
      setMatches(NO_MATCHES);
      setActiveIndex(-1);
      setSearching(false);
    }
  }

  // Смена активного чата — бар больше не относится к нему, сбрасываем результаты целиком.
  const [prevChatId, setPrevChatId] = useState(activeChatId);
  if (prevChatId !== activeChatId) {
    setPrevChatId(activeChatId);
    setMatches(NO_MATCHES);
    setActiveIndex(-1);
    setSearching(false);
    // Бар и запрос берём из адреса, а не гасим. Переход из поиска в другой чат
    // приходит двумя кадрами: адрес меняется сразу, активный чат догоняет его
    // отдельным состоянием чат-панели, — и к этому моменту блок ниже запрос уже
    // применил. Безусловный сброс стирал бы ровно тот запрос, ради которого
    // переходили, и бар не открывался вовсе. Обычное переключение чата мышью
    // адреса с запросом не несёт: там это по-прежнему закрытый пустой бар.
    setQuery(find || '');
    setOpen(!!find);
    // Сообщение из адреса относится к чату, в который переходим: у соседнего
    // такого id либо нет, либо он чужой, поэтому ждём его заново.
    setPendingMsg(msg);
  }

  // Запрос сменился в адресе — пришли по ссылке, из карточки результата или
  // нажали «Назад». Адрес меняется одним махом и всегда обгоняет activeChatId
  // (его синхронизирует эффект чат-панели), поэтому к смене чата запрос уже
  // применён этим блоком, а блок выше берёт из адреса именно его.
  const [prevFind, setPrevFind] = useState(find);
  const findChanged = prevFind !== find;
  if (findChanged) {
    setPrevFind(find);
    setQuery(find || '');
    if (find) setOpen(true);
  }

  // Другое сообщение в адресе (клик по соседней строке той же карточки). Запрос
  // при этом мог не измениться — тогда поиск не перезапустится, и переставить
  // активное совпадение больше некому. Совпадения уже есть — садимся сразу,
  // иначе ждём ответа поиска. Подстройка в рендере: состояние следует за пропом.
  //
  // Сразу — только при неизменном запросе: если сменились оба (например «Назад»
  // на ссылку в том же чате), совпадения на экране ещё от прежнего запроса, и
  // сесть по ним значило бы занять место чужим id, а ответ нового поиска потом
  // не нашёл бы, кого искали.
  const [prevMsg, setPrevMsg] = useState(msg);
  if (prevMsg !== msg) {
    setPrevMsg(msg);
    const at = findChanged ? -1 : indexOfMessage(matches, msg);
    if (at >= 0) {
      setActiveIndex(at);
      setPendingMsg('');
    } else {
      setPendingMsg(msg);
    }
  }

  // Сообщение из адреса читается на момент запроса, а не через зависимости
  // эффекта: в них оно означало бы перезапуск поиска на каждое его применение,
  // а тот сбросил бы активное совпадение обратно на самое свежее.
  const fireSearch = useEffectEvent((chatId, q) => runSearch(chatId, q, pendingMsg));

  // Поиск по дебаунсу при изменении запроса (и при открытии с готовым query).
  // Пустой запрос и закрытый бар гасят и дебаунс, и висящий запрос — сюда же
  // приходит смена чата, которая выше уже стёрла query и закрыла бар.
  useEffect(() => {
    clearTimeout(debounceRef.current);
    const q = query.trim();
    if (!open || !activeChatId || activeChatId === DRAFT_CHAT_ID || !q) {
      abortRef.current?.abort();
      return undefined;
    }
    debounceRef.current = setTimeout(() => fireSearch(activeChatId, q), DEBOUNCE_MS);
    return () => clearTimeout(debounceRef.current);
  }, [open, activeChatId, query]);

  // Шаг стрелкой — человек выбрал сам: ждать сообщение из адреса больше не надо
  // (ответ поиска, доехавший после шага, иначе увёл бы обратно на ссылку).
  const goPrev = useCallback(() => {
    stepsRef.current += 1;
    setPendingMsg('');
    setActiveIndex((i) => (matches.length ? (i - 1 + matches.length) % matches.length : -1));
  }, [matches.length]);

  const goNext = useCallback(() => {
    stepsRef.current += 1;
    setPendingMsg('');
    setActiveIndex((i) => (matches.length ? (i + 1) % matches.length : -1));
  }, [matches.length]);

  const activeMatch = activeIndex >= 0 ? matches[activeIndex] : null;

  // Приехала ли история чата. Переход из поиска открывает чат и запускает поиск
  // одним махом, а история идёт своим запросом: ответ поиска обгоняет её, и
  // догружать в этот момент нечего и нечем — чата нет ещё и в getChats. Признак
  // в зависимостях догрузки даёт ей второй заход, уже по настоящей ленте; на
  // дальнейший рост ленты он не меняется, и цикл догрузки этим не рвётся.
  const historyLoaded = (messages?.length ?? 0) > 0;

  // Догрузка старых страниц, пока активное совпадение не окажется в загруженной истории.
  useEffect(() => {
    if (!activeMatch || !activeChatId) return undefined;
    const seq = ++navSeqRef.current;
    const hasLocally = () => {
      const chat = getChats().find((c) => c.id === activeChatId);
      return !!chat?.messages?.some((m) => m.dbId === activeMatch.id);
    };
    // Первичную проверку делаем по messages (свежий снимок из рендера), а не по
    // getChats — его список синхронизируется эффектом и на один рендер отстаёт
    // (см. useChatList). Иначе для уже загруженного совпадения (обычно
    // это дефолтный — самый свежий — хит) догрузка стартует лишний раз: она проходит
    // мимо MessageList.prependRef (тот снимает scrollTop только на догрузках через
    // скролл), поэтому вставка старых сообщений сдвигает вьюпорт без компенсации —
    // уже подсвеченное сообщение мгновенно уезжает из видимой области.
    if (messages?.some((m) => m.dbId === activeMatch.id)) return undefined;
    if (hasLocally()) return undefined;

    // Пузыри, добавленные в текущей сессии (стриминг/отправка), не имеют dbId,
    // поэтому hasLocally() их не видит. Такое совпадение не старше самого старого
    // загруженного из БД сообщения (id растут вместе с курсором пагинации), и
    // листать более старые страницы ради него бессмысленно — иначе догрузили бы
    // всю историю впустую.
    const oldestLoadedDbId = getChats()
      .find((c) => c.id === activeChatId)
      ?.messages?.find((m) => m.dbId != null)?.dbId;
    if (oldestLoadedDbId != null && activeMatch.id >= oldestLoadedDbId) return undefined;

    let cancelled = false;
    (async () => {
      setNavigating(true);
      // Зеркало списка чатов обновляет эффект родителя (useChatList), а он идёт
      // после эффектов детей: сразу после перехода из поиска чата в зеркале ещё
      // нет, и hasMore по нему читался бы как «истории больше нет». Микротаска
      // хватает — к ней все эффекты коммита уже отработали.
      if (!getChats().some((c) => c.id === activeChatId)) await Promise.resolve();
      for (let i = 0; i < MAX_LOAD_STEPS; i++) {
        if (cancelled || navSeqRef.current !== seq) return;
        const chat = getChats().find((c) => c.id === activeChatId);
        if (!chat?.hasMore) break;
        const got = await loadOlderMessages(activeChatId);
        if (cancelled || navSeqRef.current !== seq) return;
        if (!got || hasLocally()) break;
      }
      if (!cancelled && navSeqRef.current === seq) setNavigating(false);
    })();
    return () => {
      cancelled = true;
    };
    // Сами messages не в deps намеренно: нужен лишь свежий снимок в момент
    // срабатывания эффекта — реагировать на каждое их изменение не нужно,
    // догрузку уже ведёт цикл внутри эффекта через getChats. Из них взят один
    // переход, historyLoaded: пустая лента не даёт ни искать, ни судить о
    // hasMore, и появление истории обязано дать эффекту второй заход.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeMatch, activeChatId, getChats, loadOlderMessages, historyLoaded]);

  return {
    open,
    query,
    setQuery,
    total: matches.length,
    activeIndex,
    loading: searching || navigating,
    activeMatchMid: resolveActiveMatchMid({ messages, matches, activeMatch, query }),
    openBar,
    close,
    // Enter и уход фокуса — точки фиксации: history.replaceState на каждую букву
    // браузеры считают злоупотреблением (Safari — с ошибкой).
    commitQuery: () => onFindChange?.(query.trim()),
    goPrev,
    goNext,
  };
}
