import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Phrases from './Phrases';
import ChipEditor from './ChipEditor';
import ComposerToolbar from './ComposerToolbar';
import { expandTokensForSend } from './fileChips';

// isEmpty — true когда в чате ещё нет сообщений; тогда показываем git-подсказки.
// Кнопки (отправить/остановить, прикрепить) и селекторы модели/режима вынесены
// под поле ввода в ComposerToolbar; здесь остаётся только само поле + подсказки.
const MessageInput = ({
  onSend,
  onStop,
  disabled,
  onAttach,
  isEmpty = false,
  resetSignal = 0,
  focusSignal = 0,
  chatId = null,
  initialText = '',
  onTextChange,
  model,
  mode,
}) => {
  const { t } = useTranslation('chat');
  // Текст инициализируем из сохранённого черновика активного чата.
  const [text, setText] = useState(initialText); // плоская строка с токенами ⟦file:…⟧
  const [sending, setSending] = useState(false); // идёт разворачивание токенов перед отправкой
  const inputRef = useRef(null);
  // Чтобы эффект resetSignal не сработал на МОНТировании (resetSignal=0) и не стёр
  // только что восстановленный из localStorage черновик — пропускаем первый прогон.
  const resetMountedRef = useRef(false);

  // Смена чата — подставляем его черновик (или пусто). Текст набирается локально,
  // поэтому родитель не ре-рендерится на каждый keystroke; черновик приезжает только
  // при переключении чата через initialText.
  useEffect(() => {
    setText(initialText);
    inputRef.current?.focus();
  }, [chatId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Внешний сброс поля ввода (например, «удаление» черновика чата). Только на реальное
  // изменение resetSignal, не на монтировании — иначе затрём восстановленный черновик.
  useEffect(() => {
    if (!resetMountedRef.current) {
      resetMountedRef.current = true;
      return;
    }
    setText('');
    onTextChange?.('');
  }, [resetSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  useEffect(() => {
    if (focusSignal) inputRef.current?.focus();
  }, [focusSignal]);

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
        <ChipEditor
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
      </div>

      <ComposerToolbar
        model={model}
        mode={mode}
        disabled={disabled}
        sendDisabled={sendDisabled}
        onAttach={onAttach}
        onStop={onStop}
        onSend={handleSubmit}
      />
    </div>
  );
};

export default MessageInput;
