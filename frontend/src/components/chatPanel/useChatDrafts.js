import { useCallback, useEffect, useRef } from 'react';
import { loadDrafts, saveDrafts, getDraft, setDraft } from './chatDrafts';

const PERSIST_DEBOUNCE_MS = 400;

/**
 * Владелец черновиков композера по чатам ({ chatId: text }, localStorage).
 * Вынесено из ChatWindow: рефы карты, отложенная запись (на каждый keystroke
 * писать не нужно) и гарантированный flush — при размонтировании и на
 * beforeunload (полная перезагрузка/закрытие вкладки не запускает cleanup
 * эффекта, поэтому одного его недостаточно).
 */
export default function useChatDrafts() {
  const draftsRef = useRef(loadDrafts());
  const persistTimerRef = useRef(null);

  const schedulePersist = useCallback(() => {
    clearTimeout(persistTimerRef.current);
    persistTimerRef.current = setTimeout(() => saveDrafts(draftsRef.current), PERSIST_DEBOUNCE_MS);
  }, []);

  /** Обновить черновик чата из поля ввода (запись на диск — отложенная). */
  const handleTextChange = useCallback(
    (id, text) => {
      setDraft(draftsRef.current, id, text);
      schedulePersist();
    },
    [schedulePersist],
  );

  /** Полностью убрать черновик чата (после отправки / удаления) и сохранить сразу. */
  const clearDraft = useCallback((id) => {
    setDraft(draftsRef.current, id, '');
    saveDrafts(draftsRef.current);
  }, []);

  /** Немедленно сбросить отложенную запись на диск (например, перед сменой чата). */
  const flushDrafts = useCallback(() => {
    clearTimeout(persistTimerRef.current);
    saveDrafts(draftsRef.current);
  }, []);

  /** Текущий черновик чата ('' если нет). */
  const getDraftFor = useCallback((id) => getDraft(draftsRef.current, id), []);

  useEffect(() => {
    window.addEventListener('beforeunload', flushDrafts);
    return () => {
      window.removeEventListener('beforeunload', flushDrafts);
      flushDrafts();
    };
  }, [flushDrafts]);

  return { getDraftFor, handleTextChange, clearDraft, flushDrafts };
}
