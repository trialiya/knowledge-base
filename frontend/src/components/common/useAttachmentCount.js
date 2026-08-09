import { useState, useEffect } from 'react';
import attachmentApi from '../../api/attachmentApi';

/**
 * Число вложений владельца (чат или документ) — для бейджа на свёрнутой правой
 * панели.
 *
 * Считать по списку нельзя: список грузит `AttachmentPanel`, а он смонтирован,
 * только когда вкладка вложений раскрыта — до первого открытия бейдж показывал
 * бы ноль независимо от того, есть вложения или нет. Поэтому берём отдельный
 * лёгкий count-эндпоинт.
 *
 * Возвращает `[count, setCount]` — обычную пару useState. Сеттер нужен
 * `AttachmentPanel`: пока он открыт, он знает точное число раньше (загрузка,
 * удаление) и держит бейдж актуальным без повторного запроса.
 *
 * `ownerId = null` (черновик чата, ничего не выбрано) — запроса нет, счёт 0.
 */
export default function useAttachmentCount(ownerType, ownerId) {
  const [count, setCount] = useState(0);

  // Сбрасываем в рендере, а не в эффекте: иначе при переключении владельца
  // бейдж на мгновение показывает чужое число.
  const [prevOwner, setPrevOwner] = useState({ ownerType, ownerId });
  if (prevOwner.ownerType !== ownerType || prevOwner.ownerId !== ownerId) {
    setPrevOwner({ ownerType, ownerId });
    setCount(0);
  }

  useEffect(() => {
    if (!ownerId) return undefined;
    let cancelled = false;
    attachmentApi
      .count(ownerType, ownerId)
      .then((value) => {
        if (!cancelled) setCount(typeof value === 'number' ? value : 0);
      })
      .catch(() => {
        if (!cancelled) setCount(0);
      });
    return () => {
      cancelled = true;
    };
  }, [ownerType, ownerId]);

  return [count, setCount];
}
