// ─── In-chat search (Ctrl+F) ────────────────────────────────────────────────
// Find-бар для одного открытого чата: ищет совпадения на бэке (история
// пагинирована, во фронте может быть загружен лишь хвост), затем даёт
// навигацию prev/next по найденным сообщениям. Если совпадение лежит в ещё
// не загруженной (более старой) странице — молча догружает её же самым
// хуком useChatMessages.loadOlderMessages, пока сообщение не появится в DOM
// (см. MessageList: именно оно делает финальный scrollIntoView по dbId).

import { useCallback, useEffect, useRef, useState } from 'react';
import chatApi from '../../api/chatApi';
import { DRAFT_CHAT_ID } from '../../constants/storage';

const DEBOUNCE_MS = 250;
// Safety cap на число страниц, которые догружаем в поисках одного совпадения —
// на случай рассинхронизации курсора не крутим цикл бесконечно.
const MAX_LOAD_STEPS = 50;

export default function useInChatSearch({ activeChatId, chatsRef, loadOlderMessages }) {
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
  }, [activeMatch, activeChatId, chatsRef, loadOlderMessages]);

  return {
    open,
    query,
    setQuery,
    total: matches.length,
    activeIndex,
    loading: searching || navigating,
    activeMatchDbId: activeMatch?.id ?? null,
    openBar,
    openWithQuery,
    close,
    goPrev,
    goNext,
  };
}
