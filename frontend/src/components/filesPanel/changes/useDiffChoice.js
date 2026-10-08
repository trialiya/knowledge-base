import { useState } from 'react';
import { previewKind } from '@/utils/filePreview';
import { UNTRACKED_STATUS } from './useUncommittedChanges';

/**
 * Что центр показывает в режиме «Изменения» — оригинал или diff открытого
 * файла. `diff` — ответ useChangeDiff про этот же файл.
 *
 * Дефолт зависит от открытого файла: у изменённого смотрят изменение, у
 * неотслеживаемого его нет вовсе — весь файл и есть новое. Поэтому выбор
 * следует пути и режиму и сбрасывается в рендере под своим prev-стражем (см.
 * правила хуков), а не эффектом, который дорисовал бы кадр с выбором,
 * сделанным для прошлого файла.
 */
export default function useDiffChoice({ path, showChanges, diff }) {
  const [diffChoice, setDiffChoice] = useState(null);
  const choiceKey = `${showChanges ? 1 : 0} ${path}`;
  const [prevChoiceKey, setPrevChoiceKey] = useState(choiceKey);
  if (prevChoiceKey !== choiceKey) {
    setPrevChoiceKey(choiceKey);
    setDiffChoice(null);
  }
  // Дефолт считаем по ответу про САМ файл, а не по списку слева: список — это
  // отдельный запрос, он приходит позже и может не прийти вовсе, и тогда центр
  // сначала показал бы исходник, а потом сам себя перерисовал в diff. По той же
  // причине центр ждёт этот ответ наравне с содержимым — иначе кадр между ними
  // показывает не то, на что кликнули (у удалённого файла — «не найдено»).
  const diffPending = showChanges && !!path && diff.loading;
  // Картинку смотрят рисунком: текстового патча у неё нет, и diff по умолчанию
  // показал бы заглушку вместо самого изменения.
  const diffByDefault = !!diff.entry && diff.entry.status !== UNTRACKED_STATUS && previewKind(path) !== 'image';
  const showDiff = showChanges && (diffChoice ?? diffByDefault);

  return { diffPending, showDiff, setDiffChoice };
}
