import React, { useState, useRef, useEffect } from 'react';

const IconSend = () => (
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2.2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <line x1="22" y1="2" x2="11" y2="13" />
    <polygon points="22 2 15 22 11 13 2 9 22 2" />
  </svg>
);

const IconStop = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
    <rect x="4" y="4" width="16" height="16" rx="3" />
  </svg>
);

const IconPaperclip = () => (
  <svg
    width="16"
    height="16"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
  </svg>
);

const MessageInput = ({ onSend, onStop, disabled, onAttach }) => {
  const [text, setText] = useState('');
  const textareaRef = useRef(null);

  useEffect(() => {
    const el = textareaRef.current;
    if (el) {
      el.style.height = 'auto';
      el.style.height = Math.min(el.scrollHeight, 150) + 'px';
    }
  }, [text]);

  useEffect(() => {
    if (!disabled) {
      textareaRef.current?.focus();
    }
  }, [disabled]);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (text.trim() && !disabled) {
        onSend(text);
        setText('');
      }
    }
  };

  const handleSubmit = () => {
    if (text.trim() && !disabled) {
      onSend(text);
      setText('');
    }
  };

  return (
    <div className="message-input-wrapper">
      {onAttach && (
        <button
          type="button"
          className="message-input-attach-btn"
          onClick={onAttach}
          title="Прикрепить файл"
          tabIndex={-1}
        >
          <IconPaperclip />
        </button>
      )}
      <textarea
        ref={textareaRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Сообщение... (Shift+Enter — новая строка)"
        disabled={disabled}
        className="message-input"
        rows={1}
      />
      <button
        type="button"
        className={
          disabled ? 'message-action-btn message-action-btn--stop' : 'message-action-btn message-action-btn--send'
        }
        onClick={disabled ? onStop : handleSubmit}
        disabled={!disabled && !text.trim()}
        title={disabled ? 'Остановить' : 'Отправить'}
      >
        {disabled ? <IconStop /> : <IconSend />}
      </button>
    </div>
  );
};

export default MessageInput;
