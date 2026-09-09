import { useCallback, useState } from 'react';
import { isEditorDirty } from '@/components/knowledgeBasePanel/editor/editorDirtyStore';

/**
 * Предупреждение о несохранённых правках при уходе из базы знаний в любой другой
 * раздел. Возвращает `goView` — им и переключают разделы вместо `switchView`.
 *
 * `goView(target, go)`: `go` делает сам переход, когда тот не сводится к смене
 * раздела — ссылка на файл открывает «Файлы» сразу на пути, одной записью
 * истории (`openFilePath`). Без `go` переход — `switchView(target)`. Пока
 * человек отвечает на вопрос, отложен переход целиком: раздел не меняется, и
 * действие ждёт подтверждения вместе с ним. Вызывать его следом за `goView`
 * нельзя — вопрос был бы задан, а уход случился бы всё равно.
 *
 * `pendingView` помнит, КУДА хотел уйти пользователь: по нему открыт диалог, а
 * после подтверждения переход идёт именно туда (chat / files / admin / settings).
 *
 * @param {object}   p
 * @param {string}   p.view        текущий раздел
 * @param {Function} p.switchView  переход без вопросов (из useAppNavigation)
 */
export default function useUnsavedViewGuard({ view, switchView }) {
  // { view, go } — отложенный переход; null — вопроса нет. Объект, а не сама
  // функция: функцию setState принял бы за апдейтер.
  const [pending, setPending] = useState(null);

  const goView = useCallback(
    (target, go) => {
      const run = go || (() => switchView(target));
      if (view === 'knowledge' && target !== 'knowledge' && isEditorDirty()) {
        setPending({ view: target, go: run });
        return;
      }
      run();
    },
    [view, switchView],
  );

  const confirmLeave = useCallback(() => {
    setPending(null);
    pending?.go();
  }, [pending]);

  const cancelLeave = useCallback(() => setPending(null), []);

  return { goView, pendingView: pending?.view ?? null, confirmLeave, cancelLeave };
}
