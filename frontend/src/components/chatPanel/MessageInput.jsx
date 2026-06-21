import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Phrases from './Phrases';
import FileChipInput from './FileChipInput';
import { expandTokensForSend } from './fileChips';
import { IconSend, IconStop, IconPaperclip } from '../../icons';

// isEmpty — true когда в чате ещё нет сообщений; тогда показываем git-подсказки
const MessageInput = ({
  onSend,
  onStop,
  disabled,
  onAttach,
  isEmpty = false,
  resetSignal = 0,
  chatId = null,
  onTextChange,
}) => {
  const { t } = useTranslation('chat');
  const [text, setText] = useState(''); // плоская строка с токенами ⟦file:…⟧
  const [sending, setSending] = useState(false); // идёт разворачивание токенов перед отправкой
  const inputRef = useRef(null);

  // Внешний сброс поля ввода (например, «удаление» черновика чата).
  useEffect(() => {
    setText('');
    onTextChange?.('');
  }, [resetSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  // Отправка: разворачиваем токены файлов в содержимое, затем отдаём наверх.
  const handleSubmit = async () => {
    if (!text.trim() || disabled || sending) return;
    setSending(true);
    try {
      const expanded = await expandTokensForSend(text);
      onSend(expanded);
      setText('');
    } finally {
      setSending(false);
    }
  };

  // Вставить выбранную git-фразу в поле ввода
  const handleSelectPhrase = (phraseText) => {
    setText(phraseText);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  const sendDisabled = !text.trim() || sending;

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
        <FileChipInput
          ref={inputRef}
          value={text}
          onChange={(v) => {
            setText(v);
            onTextChange?.(v);
          }}
          onSend={handleSubmit}
          disabled={disabled}
          placeholder={t('input.placeholder')}
          chatId={chatId}
        />
        <button
          type="button"
          className={
            disabled ? 'message-action-btn message-action-btn--stop' : 'message-action-btn message-action-btn--send'
          }
          onClick={disabled ? onStop : handleSubmit}
          disabled={!disabled && sendDisabled}
          title={disabled ? t('input.stop') : t('input.send')}
        >
          {disabled ? <IconStop /> : <IconSend />}
        </button>
      </div>
    </div>
  );
};

export default MessageInput;
