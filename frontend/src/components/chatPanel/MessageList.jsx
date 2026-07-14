// MessageList.jsx
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Message from './Message';
import { IconArrowDown } from '../../icons';
import {
  SCROLL_STICK_THRESHOLD as STICK_THRESHOLD,
  SCROLL_LOAD_THRESHOLD as LOAD_MORE_THRESHOLD,
} from '../../constants/ui';

// onLoadMore: async () => boolean — true если что-то догрузилось (для UI-индикатора).
// hasMore: есть ли ещё более старые сообщения на бэке.
// canLoadMore: разрешена ли догрузка прямо сейчас (например, false во время стриминга).
// activeSearchMid: mid пузыря, на который сейчас указывает find-бар (useInChatSearch) —
// подсвечивается и к первому вхождению запроса в нём делается программный скролл
// (один раз на mid).
// searchQuery: текущий запрос find-бара ('' — бар закрыт) — по нему подсвечиваются
// вхождения в тексте сообщений.

// Текстовые Range всех вхождений query (без учёта регистра) в текстовых узлах
// пузырей .message внутри root. Совпадение, разорванное границей узлов
// (например, markdown-форматированием), не находится — как и на бэке, где
// поиск идёт по сырому тексту, это редкий краевой случай.
const collectMatchRanges = (root, query) => {
  const q = query.toLowerCase();
  const ranges = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) =>
      node.parentElement?.closest('.message') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT,
  });
  let node;
  while ((node = walker.nextNode())) {
    const lower = node.nodeValue.toLowerCase();
    let i = lower.indexOf(q);
    while (i !== -1) {
      const r = document.createRange();
      r.setStart(node, i);
      r.setEnd(node, i + q.length);
      ranges.push(r);
      i = lower.indexOf(q, i + q.length);
    }
  }
  return ranges;
};

const setHighlight = (name, ranges) => {
  if (ranges.length) window.CSS.highlights.set(name, new window.Highlight(...ranges));
  else window.CSS.highlights.delete(name);
};

const MessageList = ({
  conversationId,
  messages,
  onNavigateToDoc,
  onLoadMore,
  onRetry,
  hasMore = false,
  canLoadMore = true,
  activeSearchMid = null,
  searchQuery = '',
}) => {
  const { t } = useTranslation('chat');
  const containerRef = useRef(null);
  // Источник правды для синхронной логики в эффектах: держимся ли у низа.
  const stickRef = useRef(true);
  const prevLenRef = useRef(messages.length);
  // Для рендера кнопки нужен re-render — держим зеркало в state.
  const [showScrollButton, setShowScrollButton] = useState(false);

  // Когда триггерим догрузку вверх — запоминаем метрики ДО вставки старых
  // сообщений, чтобы после вставки вернуть прокрутку на тот же контент.
  // null — обычный апдейт (новое сообщение / стриминг), не восстанавливаем.
  const prependRef = useRef(null); // { prevScrollHeight, prevScrollTop } | null
  const loadingMoreRef = useRef(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const isAtBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD;

  const scrollToBottom = (smooth = false) => {
    const el = containerRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    stickRef.current = true;
    setShowScrollButton(false);
  };

  // Догрузка старых сообщений при приближении к верху.
  const maybeLoadMore = useCallback(
    async (el) => {
      if (!onLoadMore || !hasMore || !canLoadMore) return;
      if (loadingMoreRef.current) return;
      if (el.scrollTop > LOAD_MORE_THRESHOLD) return;

      loadingMoreRef.current = true;
      setLoadingMore(true);
      // Снимок метрик ДО вставки. Восстановление позиции — в useLayoutEffect.
      prependRef.current = { prevScrollHeight: el.scrollHeight, prevScrollTop: el.scrollTop };
      let inserted = false;
      try {
        inserted = await onLoadMore();
      } finally {
        if (!inserted) prependRef.current = null; // ничего не вставилось — не корректируем
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    },
    [onLoadMore, hasMore, canLoadMore],
  );

  // Реакция на скролл. Программный скролл вниз тоже вызывает это событие,
  // но проверка позиции даёт atBottom=true — ложного «отлипания» не будет.
  const handleScroll = () => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = isAtBottom(el);
    stickRef.current = atBottom;
    setShowScrollButton(!atBottom);
    maybeLoadMore(el);
  };

  // Новые сообщения, стриминг ответа ИИ и догрузка старых сверху.
  // useLayoutEffect — чтобы скорректировать scrollTop до отрисовки (без мерцания).
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    // Вставили старые сообщения сверху — возвращаем прокрутку на тот же контент.
    // Автоскролл к низу при этом НЕ делаем.
    if (prependRef.current) {
      const { prevScrollHeight, prevScrollTop } = prependRef.current;
      prependRef.current = null;
      prevLenRef.current = messages.length;
      el.scrollTop = prevScrollTop + (el.scrollHeight - prevScrollHeight);
      return;
    }

    const last = messages[messages.length - 1];
    const grew = messages.length > prevLenRef.current;
    prevLenRef.current = messages.length;

    // Пользователь отправил сообщение — снова включаем автопрокрутку,
    // даже если до этого он увёл список вверх.
    if (grew && last?.sender === 'user') {
      stickRef.current = true;
      setShowScrollButton(false);
    }

    // Во время стриминга — мгновенный скролл (без smooth), иначе дёргается.
    if (stickRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  // Держим низ при изменении размеров контейнера (ресайз окна,
  // открытие/закрытие боковых панелей). На рост контента не срабатывает —
  // это обрабатывает эффект по messages.
  useEffect(() => {
    const el = containerRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => {
      if (stickRef.current) el.scrollTop = el.scrollHeight;
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Прокрутка к активному совпадению find-бара (useInChatSearch). Срабатывает ровно
  // раз на каждый activeSearchMid — как только целевой пузырь появляется в DOM,
  // будь то сразу или после догрузки более старых страниц (см. useInChatSearch).
  // Повторных скроллов при последующих обновлениях messages для того же mid не делаем.
  const scrolledSearchMidRef = useRef(null);
  useEffect(() => {
    if (activeSearchMid == null) {
      scrolledSearchMidRef.current = null;
      return;
    }
    if (scrolledSearchMidRef.current === activeSearchMid) return;
    const container = containerRef.current;
    const el = container?.querySelector(`[data-mid="${activeSearchMid}"]`);
    if (!el) return;
    scrolledSearchMidRef.current = activeSearchMid;
    stickRef.current = false; // не залипаем к низу при программной прокрутке к совпадению
    // Длинное сообщение может быть выше экрана — центрируем не пузырь целиком,
    // а первое вхождение запроса в нём.
    const range = searchQuery ? collectMatchRanges(el, searchQuery)[0] : null;
    if (range) {
      const rect = range.getBoundingClientRect();
      const contRect = container.getBoundingClientRect();
      container.scrollTo({
        top: container.scrollTop + (rect.top - contRect.top) - container.clientHeight / 2,
        behavior: 'smooth',
      });
    } else {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [activeSearchMid, searchQuery, messages]);

  // Подсветка вхождений запроса в тексте сообщений (CSS Custom Highlight API,
  // без вмешательства в DOM, которым управляет React). Вхождения в активном
  // пузыре подсвечиваются отдельным, более контрастным стилем. В браузерах без
  // поддержки подсветки нет — остаётся рамка вокруг активного пузыря.
  useEffect(() => {
    if (!window.CSS?.highlights) return undefined;
    const container = containerRef.current;
    const q = searchQuery.trim();
    if (!container || !q) {
      window.CSS.highlights.delete('kb-chat-find');
      window.CSS.highlights.delete('kb-chat-find-active');
      return undefined;
    }
    const all = collectMatchRanges(container, q);
    const activeEl = activeSearchMid != null ? container.querySelector(`[data-mid="${activeSearchMid}"]`) : null;
    const active = activeEl ? all.filter((r) => activeEl.contains(r.startContainer)) : [];
    const rest = activeEl ? all.filter((r) => !activeEl.contains(r.startContainer)) : all;
    setHighlight('kb-chat-find', rest);
    setHighlight('kb-chat-find-active', active);
    return () => {
      window.CSS.highlights.delete('kb-chat-find');
      window.CSS.highlights.delete('kb-chat-find-active');
    };
  }, [searchQuery, activeSearchMid, messages]);

  return (
    <div className="message-list-container">
      {loadingMore && <div className="message-list-loading-older">{t('window.loadingMessages')}</div>}

      <div className="message-list" ref={containerRef} onScroll={handleScroll}>
        {messages.map((msg, index) => (
          <Message
            key={msg.mid ?? index}
            text={msg.text}
            sender={msg.sender}
            toolCalls={msg.toolCalls}
            timestamp={msg.timestamp}
            toolCallsRunId={msg.toolCallsRunId}
            preparing={msg.preparing}
            error={msg.error}
            onRetry={onRetry && msg.error ? () => onRetry(msg.mid) : undefined}
            conversationId={conversationId}
            onNavigateToDoc={onNavigateToDoc}
            mid={msg.mid}
            searchActive={msg.mid != null && msg.mid === activeSearchMid}
          />
        ))}
      </div>

      {showScrollButton && (
        <button
          type="button"
          className="scroll-to-bottom-btn"
          onClick={() => scrollToBottom(true)}
          title={t('scroll.toLatest')}
          aria-label={t('scroll.scrollDown')}
        >
          <IconArrowDown />
        </button>
      )}
    </div>
  );
};

export default MessageList;
