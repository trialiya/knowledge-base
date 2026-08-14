import { useCallback, useState } from 'react';
import { isEditorDirty } from './components/knowledgeBasePanel/editorDirtyStore';

/**
 * Предупреждение о несохранённых правках при уходе из базы знаний в любой другой
 * раздел. Возвращает `goView` — им и переключают разделы вместо `switchView`.
 *
 * `pendingView` помнит, КУДА хотел уйти пользователь, чтобы после подтверждения
 * перейти именно туда (chat / files / admin / settings), а не только в чат.
 *
 * @param {object}   p
 * @param {string}   p.view        текущий раздел
 * @param {Function} p.switchView  переход без вопросов (из useAppNavigation)
 */
export default function useUnsavedViewGuard({ view, switchView }) {
  const [pendingView, setPendingView] = useState(null);

  const goView = useCallback(
    (target) => {
      if (view === 'knowledge' && target !== 'knowledge' && isEditorDirty()) {
        setPendingView(target);
        return;
      }
      switchView(target);
    },
    [view, switchView],
  );

  const confirmLeave = useCallback(() => {
    setPendingView(null);
    if (pendingView) switchView(pendingView);
  }, [pendingView, switchView]);

  const cancelLeave = useCallback(() => setPendingView(null), []);

  return { goView, pendingView, confirmLeave, cancelLeave };
}
