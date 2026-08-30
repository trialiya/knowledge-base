import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import Phrases from './Phrases';
import RunStatus from './RunStatus';
import PhraseFillModal from './PhraseFillModal';
import ChipEditor from './ChipEditor';
import ComposerToolbar from './ComposerToolbar';
import ContextChips from './ContextChips';
import { expandTokensForSend } from './fileChips';
import { parsePlaceholders } from './phrasePlaceholders';

// isEmpty — true когда в чате ещё нет сообщений; тогда показываем git-подсказки.
// busy — писать некуда: идёт сжатие контекста или прогон ещё не назвал свой runId.
// generating — идёт ответ модели. Поле при этом НЕ блокируется: сообщение встаёт в
// очередь прогона (см. useChatRun), а «остановить» просто добавляется рядом с «отправить».
// stoppable — есть ли что останавливать. Неактивной кнопка «остановить» показывается в двух
// случаях: сжатие контекста (/compact) прерывать нечем — своего дескриптора прогона у него нет,
// — и окно до ответа на POST /runs, пока runId неизвестен и адресовать остановку некуда.
// active — панель чата открыта (не перекрыта другим разделом): по ней ставится фокус.
// run — детали идущей занятости для строки над полем ({ startedAt, inputGrowth }, см. RunStatus);
// null — чат свободен. У сжатия контекста строка тоже есть: прироста input у него не бывает, но
// таймер нужен ему сильнее всех — идёт оно дольше среднего ответа.
// Кнопки (отправить/остановить, прикрепить) и селекторы модели/режима вынесены
// под поле ввода в ComposerToolbar; здесь остаётся только само поле + подсказки.
const MessageInput = ({
  onSend,
  onStop,
  busy,
  generating = false,
  stoppable = true,
  onAttach,
  isEmpty = false,
  draftSignal = 0,
  active = true,
  chatId = null,
  initialText = '',
  onTextChange,
  model,
  mode,
  project,
  staged,
  onUnstage,
  run = null,
}) => {
  const { t } = useTranslation('chat');
  // Текст инициализируем из сохранённого черновика активного чата.
  const [text, setText] = useState(initialText); // плоская строка с токенами ⟦file:…⟧
  const [sending, setSending] = useState(false); // идёт разворачивание токенов перед отправкой
  const [pendingPhrase, setPendingPhrase] = useState(null); // { text, label } фразы, ждущей заполнения
  const inputRef = useRef(null);
  // Чтобы эффект draftSignal не сработал на МОНТировании (draftSignal=0) и не стёр
  // только что восстановленный из localStorage черновик — пропускаем первый прогон.
  const draftSignalMountedRef = useRef(false);

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

  // Черновик переписали снаружи — «удалением» черновика чата, вычисткой чипов
  // прежнего проекта — и поле обязано показать новую версию: текст живёт здесь, и
  // без этого правка осталась бы только в хранилище. Сигнал не несёт самого текста:
  // хранилище уже обновлено, и родитель отдал его в initialText этим же рендером.
  // Только на реальное изменение draftSignal, не на монтировании — иначе затрём
  // восстановленный черновик.
  useEffect(() => {
    if (!draftSignalMountedRef.current) {
      draftSignalMountedRef.current = true;
      return;
    }
    setText(initialText);
  }, [draftSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!busy) inputRef.current?.focus();
  }, [busy]);

  // Панель чата смонтирована всегда, поверх неё бывают другие разделы — при
  // возврате на неё фокус снова уходит в поле ввода.
  useEffect(() => {
    if (active) inputRef.current?.focus();
  }, [active]);

  // Отправка: разворачиваем токены файлов в содержимое, затем отдаём наверх.
  const handleSubmit = async () => {
    if (!text.trim() || busy || sending) return;
    setSending(true);
    try {
      const expanded = await expandTokensForSend(text, project?.selected);
      onSend(expanded);
      setText('');
    } finally {
      setSending(false);
    }
  };

  // Фокус возвращаем и после вставки, и после отмены: диалог забрал его себе, и
  // без этого он остался бы на body — следующий Tab пошёл бы обходить страницу.
  // Каретку при этом ставим в конец: focus() на contenteditable роняет её в
  // начало, и первый же символ уехал бы в начало черновика. setTimeout нужен
  // вставке — до перерисовки поля (эффект ChipEditor по value) в нём ещё лежит
  // прежний текст, и «конец» был бы его концом.
  const focusInputEnd = () => setTimeout(() => inputRef.current?.focusEnd(), 0);

  const insertPhrase = (phraseText) => {
    setText(phraseText);
    onTextChange?.(phraseText);
    focusInputEnd();
  };

  // Фраза с плейсхолдерами сначала уходит в диалог заполнения, остальные
  // вставляются сразу.
  const handleSelectPhrase = (phraseText, phraseLabel) => {
    if (parsePlaceholders(phraseText).length > 0) setPendingPhrase({ text: phraseText, label: phraseLabel });
    else insertPhrase(phraseText);
  };

  const sendDisabled = !text.trim() || sending;

  return (
    <div className="message-input-area">
      {/* Блок git-фраз — только когда чат пустой */}
      {isEmpty && <Phrases onSelect={handleSelectPhrase} />}

      {pendingPhrase !== null && (
        <PhraseFillModal
          phraseText={pendingPhrase.text}
          phraseLabel={pendingPhrase.label}
          project={project?.selected}
          onSubmit={(filled) => {
            setPendingPhrase(null);
            insertPhrase(filled);
          }}
          onCancel={() => {
            setPendingPhrase(null);
            focusInputEnd();
          }}
        />
      )}

      <ContextChips items={staged} onRemove={onUnstage} ariaLabel={t('contextItems.staged')} />

      {/* Детали идущего прогона — поле при нём не блокируется, и строка объясняет, чем чат занят. */}
      {run && <RunStatus startedAt={run.startedAt} inputGrowth={run.inputGrowth} />}

      <div className="message-input-wrapper">
        <ChipEditor
          ref={inputRef}
          project={project?.selected}
          value={text}
          onChange={(v) => {
            setText(v);
            onTextChange?.(v);
          }}
          onSend={handleSubmit}
          disabled={busy}
          placeholder={t('input.placeholder')}
          chatId={chatId}
        />
      </div>

      <ComposerToolbar
        model={model}
        mode={mode}
        project={project}
        busy={busy}
        generating={generating}
        stoppable={stoppable}
        sendDisabled={sendDisabled}
        onAttach={onAttach}
        onStop={onStop}
        onSend={handleSubmit}
      />
    </div>
  );
};

export default MessageInput;
