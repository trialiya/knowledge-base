// ─── Поиск внутри открытой модалки (Ctrl+F) ─────────────────────────────────
// Пока открыта модалка, Ctrl+F ищет ТОЛЬКО по её содержимому: браузерный поиск
// прошёлся бы по всей странице, включая скрытый под оверлеем интерфейс, до
// которого пользователю сейчас нет дела.
//
// Сам поиск — общий useFindMatches; здесь только то, чем модалка отличается:
// шорткат достаётся верхней из стопки, а запрос живёт ровно столько, сколько
// открыт диалог.

import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react';
import useFindMatches from '@/components/common/search/useFindMatches';

/**
 * @param dialogRef ref на бокс диалога — область поиска
 * @param active    открыта ли сама модалка
 * @param isTopmost () => boolean, верхняя ли она в стопке (шорткат ловит только она)
 */
export default function useModalFind({ dialogRef, active, isTopmost }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const inputRef = useRef(null);

  // Модалку закрыли — бар к ней больше не относится, следующее открытие
  // начинается с чистого.
  const [prevActive, setPrevActive] = useState(active);
  if (prevActive !== active) {
    setPrevActive(active);
    if (!active) {
      setOpen(false);
      setQuery('');
    }
  }

  const matches = useFindMatches({ rootRef: dialogRef, query, active: open });

  const close = useCallback(() => {
    setOpen(false);
    setQuery('');
  }, []);

  // Тело шортката — useEffectEvent: слушатель вешается один раз на открытие
  // модалки, но читает всегда свежие open/query.
  const onFindKey = useEffectEvent((e) => {
    if (!isTopmost()) return;
    // Гасим браузерный поиск по странице — ради этого всё и затевалось.
    e.preventDefault();
    setOpen(true);
    if (open) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  });

  useEffect(() => {
    if (!active) return undefined;
    const onKeyDown = (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      // e.code — физическая клавиша: на нелатинских раскладках e.key даёт символ
      // раскладки («а»), и проверка только по key ломает шорткат.
      if (e.key !== 'f' && e.key !== 'F' && e.code !== 'KeyF') return;
      onFindKey(e);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [active]);

  return {
    open,
    query,
    total: matches.total,
    activeIndex: matches.activeIndex,
    inputRef,
    onQueryChange: setQuery,
    close,
    goPrev: matches.goPrev,
    goNext: matches.goNext,
  };
}
