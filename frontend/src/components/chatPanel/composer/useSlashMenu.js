import { useCallback, useMemo, useState } from 'react';
import { slashMenuItems } from './slashMenu';

/**
 * Состояние списка со слэша: что показать (считает slashMenuItems по набранному)
 * и что в нём выбрано.
 *
 * Открытость списка — не состояние, а следствие набранного: пока текст остаётся
 * слэш-префиксом, список уместен. Отдельно живёт только закрытие по Escape — и
 * снимается оно следующим же изменением текста: Escape закрывает список для
 * того, что набрано сейчас, а не слэш вообще.
 */
export default function useSlashMenu(value) {
  const items = useMemo(() => slashMenuItems(value) || [], [value]);
  const [nav, setNav] = useState({ value, idx: 0, dismissed: false });

  if (nav.value !== value) setNav({ value, idx: 0, dismissed: false });

  const last = items.length - 1;
  const move = useCallback(
    (delta) => setNav((p) => ({ ...p, idx: Math.min(Math.max(p.idx + delta, 0), Math.max(last, 0)) })),
    [last],
  );
  const dismiss = useCallback(() => setNav((p) => ({ ...p, dismissed: true })), []);

  const open = items.length > 0 && !nav.dismissed;
  const idx = Math.min(nav.idx, Math.max(last, 0));
  // Ссылка на результат стабильна, пока стабильно состояние: на ней держится
  // мемоизация обработчика клавиш в ChipEditor.
  return useMemo(() => ({ items, open, idx, move, dismiss }), [items, open, idx, move, dismiss]);
}
