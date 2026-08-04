import { useCallback, useEffect, useRef, useState } from 'react';
import { loadDrafts, saveDrafts, getDraft, setDraft, loadStaged, saveStaged, getStaged, setStaged } from './chatDrafts';

const PERSIST_DEBOUNCE_MS = 400;

/**
 * Владелец черновиков композера по чатам (localStorage).
 * Вынесено из ChatWindow: рефы карты, отложенная запись (на каждый keystroke
 * писать не нужно) и гарантированный flush — при размонтировании и на
 * beforeunload (полная перезагрузка/закрытие вкладки не запускает cleanup
 * эффекта, поэтому одного его недостаточно).
 *
 * Черновик — это две вещи: набранный текст и отложенные к отправке вложения.
 * Текст живёт в рефе (его владелец — само поле ввода, родителю на каждый
 * keystroke ре-рендериться незачем), а отложенные вложения — в state: их видят
 * и композер (чипы), и отправка, и меняются они по клику, а не по букве.
 */
export default function useChatDrafts() {
  const draftsRef = useRef(loadDrafts());
  const persistTimerRef = useRef(null);
  const [stagedByChat, setStagedByChat] = useState(loadStaged);

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

  // Отложенные вложения меняются по клику, а не по букве, поэтому пишем сразу.
  const updateStaged = useCallback((id, next) => {
    setStagedByChat((prev) => {
      const map = { ...prev };
      setStaged(map, id, next(getStaged(map, id)));
      saveStaged(map);
      return map;
    });
  }, []);

  /** Отложить вложение к следующему сообщению чата (повторное — no-op). */
  const stageContextItem = useCallback(
    (id, item) => updateStaged(id, (list) => (list.some((i) => i.ref === item.ref) ? list : [...list, item])),
    [updateStaged],
  );

  const unstageContextItem = useCallback(
    (id, ref) => updateStaged(id, (list) => list.filter((i) => i.ref !== ref)),
    [updateStaged],
  );

  /** Полностью убрать черновик чата (после отправки / удаления) и сохранить сразу. */
  const clearDraft = useCallback(
    (id) => {
      setDraft(draftsRef.current, id, '');
      saveDrafts(draftsRef.current);
      updateStaged(id, () => []);
    },
    [updateStaged],
  );

  /** Немедленно сбросить отложенную запись на диск (например, перед сменой чата). */
  const flushDrafts = useCallback(() => {
    clearTimeout(persistTimerRef.current);
    saveDrafts(draftsRef.current);
  }, []);

  /** Текущий черновик чата ('' если нет). */
  const getDraftFor = useCallback((id) => getDraft(draftsRef.current, id), []);

  /** Отложенные вложения чата (стабильный пустой массив, если их нет). */
  const getStagedFor = useCallback((id) => getStaged(stagedByChat, id), [stagedByChat]);

  useEffect(() => {
    window.addEventListener('beforeunload', flushDrafts);
    return () => {
      window.removeEventListener('beforeunload', flushDrafts);
      flushDrafts();
    };
  }, [flushDrafts]);

  return {
    getDraftFor,
    handleTextChange,
    clearDraft,
    flushDrafts,
    getStagedFor,
    stageContextItem,
    unstageContextItem,
  };
}
