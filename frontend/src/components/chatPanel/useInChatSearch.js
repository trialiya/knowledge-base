// ─── In-chat search (Ctrl+F) ────────────────────────────────────────────────
// Find-бар для одного открытого чата: ищет совпадения на бэке (история
// пагинирована, во фронте может быть загружен лишь хвост), затем даёт
// навигацию prev/next по найденным сообщениям. Если совпадение лежит в ещё
// не загруженной (более старой) странице — молча догружает её же самым
// хуком useChatMessages.loadOlderMessages, пока сообщение не появится в DOM
// (см. MessageList: именно оно делает финальный скролл и подсветку по mid).

import { useCallback, useEffect, useRef, useState } from 'react';
import chatApi from '../../api/chatApi';
import { DRAFT_CHAT_ID } from '../../constants/storage';

const DEBOUNCE_MS = 250;
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

export default function useInChatSearch({ activeChatId, chatsRef, loadOlderMessages, messages }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [matches, setMatches] = useState([]); // [{ id, createdAt }] хронологически (ASC)
  const [activeIndex, setActiveIndex] = useState(-1);
  const [searching, setSearching] = useState(false);
  const [navigating, setNavigating] = useState(false);

  const debounceRef = useRef(null);
  const abortRef = useRef(null);
  const navSeqRef = useRef(0); // гасит устаревшую навигацию (чат/индекс сменились по пути)
  // Разовый флаг для openWithQuery: сигнализирует эффекту смены activeChatId «это
  // переход из поиска по чатам, не закрывай/не стирай то, что мы только что открыли».
  // Сбрасывается либо этим эффектом (реальное переключение), либо таймером ниже
  // (чат не менялся — эффект вообще не сработает, флаг не должен «дожить» до
  // следующего, уже обычного переключения чата).
  const pendingOpenRef = useRef(false);

  const resetResults = useCallback(() => {
    clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    setMatches([]);
    setActiveIndex(-1);
    setSearching(false);
  }, []);

  const close = useCallback(() => {
    resetResults();
    setOpen(false);
    setQuery('');
  }, [resetResults]);

  const openBar = useCallback(() => setOpen(true), []);

  // Открыть с уже готовым запросом (переход из поиска по чатам в сайдбаре).
  // Дефолтная посадка на самое свежее совпадение — то же сообщение, из
  // которого там же построен сниппет, так что переход выглядит бесшовным.
  const openWithQuery = useCallback((q) => {
    pendingOpenRef.current = true;
    setOpen(true);
    setQuery(q);
    // Если activeChatId фактически не меняется (выбран уже открытый чат), эффект
    // ниже не сработает и не снимет флаг сам — снимаем его здесь с задержкой,
    // чтобы он не «выстрелил» при следующем обычном переключении чата.
    setTimeout(() => {
      pendingOpenRef.current = false;
    }, 0);
  }, []);

  const runSearch = useCallback((chatId, q) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setSearching(true);
    chatApi
      .searchMessages(chatId, q, controller.signal)
      .then((data) => {
        const list = Array.isArray(data) ? data : [];
        setMatches(list);
        setActiveIndex(list.length ? list.length - 1 : -1); // самое свежее по умолчанию
        setSearching(false);
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          setMatches([]);
          setActiveIndex(-1);
          setSearching(false);
        }
      });
  }, []);

  // Поиск по дебаунсу при изменении запроса (и при открытии с готовым query).
  useEffect(() => {
    if (!open || !activeChatId || activeChatId === DRAFT_CHAT_ID) return undefined;
    clearTimeout(debounceRef.current);
    const q = query.trim();
    if (!q) {
      abortRef.current?.abort();
      setMatches([]);
      setActiveIndex(-1);
      setSearching(false);
      return undefined;
    }
    debounceRef.current = setTimeout(() => runSearch(activeChatId, q), DEBOUNCE_MS);
    return () => clearTimeout(debounceRef.current);
  }, [open, activeChatId, query, runSearch]);

  // Смена активного чата — бар больше не относится к нему, сбрасываем результаты целиком.
  // Исключение: переход из openWithQuery — там открытие уже выставлено намеренно.
  useEffect(() => {
    if (pendingOpenRef.current) {
      pendingOpenRef.current = false;
      return;
    }
    resetResults();
    setOpen(false);
    setQuery('');
    // activeChatId — единственная значимая зависимость: сбрасываем именно при переключении чата.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeChatId]);

  const goPrev = useCallback(() => {
    setActiveIndex((i) => (matches.length ? (i - 1 + matches.length) % matches.length : -1));
  }, [matches.length]);

  const goNext = useCallback(() => {
    setActiveIndex((i) => (matches.length ? (i + 1) % matches.length : -1));
  }, [matches.length]);

  const activeMatch = activeIndex >= 0 ? matches[activeIndex] : null;

  // Догрузка старых страниц, пока активное совпадение не окажется в загруженной истории.
  useEffect(() => {
    if (!activeMatch || !activeChatId) return undefined;
    const seq = ++navSeqRef.current;
    const hasLocally = () => {
      const chat = chatsRef.current.find((c) => c.id === activeChatId);
      return !!chat?.messages?.some((m) => m.dbId === activeMatch.id);
    };
    // Первичную проверку делаем по messages (свежий снимок из рендера), а не по
    // chatsRef — он синхронизируется отдельным эффектом и на один рендер отстаёт
    // (см. комментарий в ChatWindow). Иначе для уже загруженного совпадения (обычно
    // это дефолтный — самый свежий — хит) догрузка стартует лишний раз: она проходит
    // мимо MessageList.prependRef (тот снимает scrollTop только на догрузках через
    // скролл), поэтому вставка старых сообщений сдвигает вьюпорт без компенсации —
    // уже подсвеченное сообщение мгновенно уезжает из видимой области.
    if (messages.some((m) => m.dbId === activeMatch.id)) return undefined;
    if (hasLocally()) return undefined;

    // Пузыри, добавленные в текущей сессии (стриминг/отправка), не имеют dbId,
    // поэтому hasLocally() их не видит. Такое совпадение не старше самого старого
    // загруженного из БД сообщения (id растут вместе с курсором пагинации), и
    // листать более старые страницы ради него бессмысленно — иначе догрузили бы
    // всю историю впустую.
    const oldestLoadedDbId = chatsRef.current
      .find((c) => c.id === activeChatId)
      ?.messages?.find((m) => m.dbId != null)?.dbId;
    if (oldestLoadedDbId != null && activeMatch.id >= oldestLoadedDbId) return undefined;

    let cancelled = false;
    (async () => {
      setNavigating(true);
      for (let i = 0; i < MAX_LOAD_STEPS; i++) {
        if (cancelled || navSeqRef.current !== seq) return;
        const chat = chatsRef.current.find((c) => c.id === activeChatId);
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
    // messages не в deps намеренно: нужен лишь свежий снимок в момент срабатывания
    // эффекта (смена activeMatch/activeChatId) — реагировать на его последующие
    // изменения не нужно, догрузку уже ведёт цикл внутри эффекта через chatsRef.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeMatch, activeChatId, chatsRef, loadOlderMessages]);

  return {
    open,
    query,
    setQuery,
    total: matches.length,
    activeIndex,
    loading: searching || navigating,
    activeMatchMid: resolveActiveMatchMid({ messages, matches, activeMatch, query }),
    openBar,
    openWithQuery,
    close,
    goPrev,
    goNext,
  };
}
