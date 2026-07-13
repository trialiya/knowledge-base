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
// activeSearchDbId: id (из БД) сообщения, на которое сейчас указывает find-бар (useInChatSearch) —
// подсвечивается и по нему делается программный scrollIntoView (один раз на id).
const MessageList = ({
  conversationId,
  messages,
  onNavigateToDoc,
  onLoadMore,
  onRetry,
  hasMore = false,
  canLoadMore = true,
  activeSearchDbId = null,
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
  // раз на каждый activeSearchDbId — как только целевой пузырь появляется в DOM,
  // будь то сразу или после догрузки более старых страниц (см. useInChatSearch).
  // Повторных скроллов при последующих обновлениях messages для того же id не делаем.
  const scrolledSearchIdRef = useRef(null);
  useEffect(() => {
    if (activeSearchDbId == null) {
      scrolledSearchIdRef.current = null;
      return;
    }
    if (scrolledSearchIdRef.current === activeSearchDbId) return;
    const el = containerRef.current?.querySelector(`[data-db-id="${activeSearchDbId}"]`);
    if (!el) return;
    scrolledSearchIdRef.current = activeSearchDbId;
    stickRef.current = false; // не залипаем к низу при программной прокрутке к совпадению
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [activeSearchDbId, messages]);

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
            onRetry={onRetry && msg.error ? () => onRetry(index) : undefined}
            conversationId={conversationId}
            onNavigateToDoc={onNavigateToDoc}
            dbId={msg.dbId}
            searchActive={msg.dbId != null && msg.dbId === activeSearchDbId}
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
