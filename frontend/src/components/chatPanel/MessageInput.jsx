import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Phrases from './Phrases';
import { IconSend, IconStop, IconPaperclip } from '../../icons';

// isEmpty — true когда в чате ещё нет сообщений; тогда показываем git-подсказки
const MessageInput = ({ onSend, onStop, disabled, onAttach, isEmpty = false, resetSignal = 0 }) => {
  const { t } = useTranslation('chat');
  const [text, setText] = useState('');
  const textareaRef = useRef(null);

  // Внешний сброс поля ввода (например, «удаление» черновика чата).
  // На первый рендер тоже сработает — там очистка пустого поля безвредна.
  useEffect(() => {
    setText('');
  }, [resetSignal]);

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

  // Вставить выбранную git-фразу в textarea и поставить курсор в конец
  const handleSelectPhrase = (phraseText) => {
    setText(phraseText);
    setTimeout(() => {
      const el = textareaRef.current;
      if (el) {
        el.focus();
        el.setSelectionRange(el.value.length, el.value.length);
      }
    }, 0);
  };

  return (
    <div className="message-input-area">
      {/* Блок git-фраз — только когда чат пустой */}
      {isEmpty && <Phrases onSelect={handleSelectPhrase} />}

      <div className="message-input-wrapper">
        {onAttach && (
          <button
            type="button"
            className="message-input-attach-btn"
            onClick={onAttach}
            title={t('input.attach')}
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
          placeholder={t('input.placeholder')}
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
          title={disabled ? t('input.stop') : t('input.send')}
        >
          {disabled ? <IconStop /> : <IconSend />}
        </button>
      </div>
    </div>
  );
};

export default MessageInput;
