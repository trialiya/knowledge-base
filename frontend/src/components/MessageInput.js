import React, { useState, useRef, useEffect } from 'react';

const MessageInput = ({ onSend, onStop, disabled }) => {
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

  const handleSubmit = (e) => {
    e.preventDefault();
    if (text.trim() && !disabled) {
      onSend(text);
      setText('');
    }
  };

  return (
    <form onSubmit={handleSubmit} className="message-input-form">
      <textarea
        ref={textareaRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Введите ваш запрос... (Shift+Enter — новая строка)"
        disabled={disabled}
        className="message-input"
        rows={1}
      />
      {disabled ? (
        <button type="button" onClick={onStop} className="stop-button">
          Остановить
        </button>
      ) : (
        <button type="submit" disabled={!text.trim()} className="send-button">
          Отправить
        </button>
      )}
    </form>
  );
};

export default MessageInput;
