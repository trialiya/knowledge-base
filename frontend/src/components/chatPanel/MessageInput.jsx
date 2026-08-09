import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Phrases from './Phrases';
import PhraseFillModal from './PhraseFillModal';
import ChipEditor from './ChipEditor';
import ComposerToolbar from './ComposerToolbar';
import ContextChips from './ContextChips';
import { expandTokensForSend } from './fileChips';
import { parsePlaceholders } from './phrasePlaceholders';

// isEmpty — true когда в чате ещё нет сообщений; тогда показываем git-подсказки.
// active — панель чата открыта (не перекрыта другим разделом): по ней ставится фокус.
// Кнопки (отправить/остановить, прикрепить) и селекторы модели/режима вынесены
// под поле ввода в ComposerToolbar; здесь остаётся только само поле + подсказки.
const MessageInput = ({
  onSend,
  onStop,
  disabled,
  onAttach,
  isEmpty = false,
  resetSignal = 0,
  active = true,
  chatId = null,
  initialText = '',
  onTextChange,
  model,
  mode,
  staged,
  onUnstage,
}) => {
  const { t } = useTranslation('chat');
  // Текст инициализируем из сохранённого черновика активного чата.
  const [text, setText] = useState(initialText); // плоская строка с токенами ⟦file:…⟧
  const [sending, setSending] = useState(false); // идёт разворачивание токенов перед отправкой
  const [pendingPhrase, setPendingPhrase] = useState(null); // фраза, ждущая заполнения плейсхолдеров
  const inputRef = useRef(null);
  // Чтобы эффект resetSignal не сработал на МОНТировании (resetSignal=0) и не стёр
  // только что восстановленный из localStorage черновик — пропускаем первый прогон.
  const resetMountedRef = useRef(false);

  // Смена чата — подставляем его черновик (или пусто). Текст набирается локально,
  // поэтому родитель не ре-рендерится на каждый keystroke; черновик приезжает только
  // при переключении чата через initialText. Подстановка в рендере, а не эффектом:
  // иначе первый кадр нового чата показывал бы черновик предыдущего.
  const [prevChatId, setPrevChatId] = useState(chatId);
  if (prevChatId !== chatId) {
    setPrevChatId(chatId);
    setText(initialText);
  }

  useEffect(() => {
    inputRef.current?.focus();
  }, [chatId]);

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

  // Панель чата смонтирована всегда, поверх неё бывают другие разделы — при
  // возврате на неё фокус снова уходит в поле ввода.
  useEffect(() => {
    if (active) inputRef.current?.focus();
  }, [active]);

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

  const insertPhrase = (phraseText) => {
    setText(phraseText);
    onTextChange?.(phraseText);
    setTimeout(() => inputRef.current?.focus(), 0);
  };

  // Фраза с плейсхолдерами сначала уходит в диалог заполнения, остальные
  // вставляются сразу.
  const handleSelectPhrase = (phraseText) => {
    if (parsePlaceholders(phraseText).length > 0) setPendingPhrase(phraseText);
    else insertPhrase(phraseText);
  };

  const sendDisabled = !text.trim() || sending;

  return (
    <div className="message-input-area">
      {/* Блок git-фраз — только когда чат пустой */}
      {isEmpty && <Phrases onSelect={handleSelectPhrase} />}

      {pendingPhrase !== null && (
        <PhraseFillModal
          phraseText={pendingPhrase}
          onSubmit={(filled) => {
            setPendingPhrase(null);
            insertPhrase(filled);
          }}
          onCancel={() => setPendingPhrase(null)}
        />
      )}

      <ContextChips items={staged} onRemove={onUnstage} ariaLabel={t('contextItems.staged')} />

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
