// MessageList.jsx
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Message from './Message';

// Насколько близко к низу считаем, что мы «прилипли» (px).
// Порог обязателен: из-за субпиксельных значений scrollHeight/clientHeight
// строгое сравнение с нулём иногда не детектит «у самого низа».
const STICK_THRESHOLD = 60;

const IconArrowDown = () => (
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2.4"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <line x1="12" y1="5" x2="12" y2="19" />
    <polyline points="19 12 12 19 5 12" />
  </svg>
);

const MessageList = ({ messages, onNavigateToDoc }) => {
  const { t } = useTranslation();
  const containerRef = useRef(null);
  // Источник правды для синхронной логики в эффектах: держимся ли у низа.
  const stickRef = useRef(true);
  const prevLenRef = useRef(messages.length);
  // Для рендера кнопки нужен re-render — держим зеркало в state.
  const [showScrollButton, setShowScrollButton] = useState(false);

  const isAtBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD;

  const scrollToBottom = (smooth = false) => {
    const el = containerRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    stickRef.current = true;
    setShowScrollButton(false);
  };

  // Реакция на скролл. Программный скролл вниз тоже вызывает это событие,
  // но проверка позиции даёт atBottom=true — ложного «отлипания» не будет.
  const handleScroll = () => {
    const el = containerRef.current;
    if (!el) return;
    const atBottom = isAtBottom(el);
    stickRef.current = atBottom;
    setShowScrollButton(!atBottom);
  };

  // Новые сообщения и стриминг ответа ИИ.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

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

  return (
    <div className="message-list-container">
      <div className="message-list" ref={containerRef} onScroll={handleScroll}>
        {messages.map((msg, index) => (
          <Message
            key={index}
            text={msg.text}
            sender={msg.sender}
            toolCalls={msg.toolCalls}
            onNavigateToDoc={onNavigateToDoc}
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
