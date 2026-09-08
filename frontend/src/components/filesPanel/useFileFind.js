import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react';
import useFindMatches from '@/components/common/search/useFindMatches';
import { hasOverlay } from '@/components/common/layout/overlayStack';

/**
 * Find-бар над открытым файлом: то же, что Ctrl+F в модалке, но запрос приносит
 * адрес.
 *
 * Переход из единого поиска кладёт в адрес `find` (и `re=1`, если искали
 * регуляркой) — бар открывается сам, с подставленным запросом, и центр
 * проматывается к первому совпадению. Иначе клик по найденному открывал бы файл
 * на первой строке, и совпадение пришлось бы искать заново глазами.
 *
 * Набранное в поле живёт в локальном черновике, а в адрес уходит по Enter, уходу
 * фокуса и закрытию бара — как в фильтрах поиска: подсветка обязана идти за
 * каждой буквой, а вот history.replaceState на каждую букву браузеры считают
 * злоупотреблением (Safari — с ошибкой).
 *
 * @param rootRef  ref на прокручиваемое тело файла — область поиска
 * @param find     запрос из адреса ('' — файл открыт не из поиска)
 * @param regex    читать ли запрос как регулярное выражение
 * @param onCommit (find, regex) — записать запрос в адрес
 */
export default function useFileFind({ rootRef, find, regex, onCommit }) {
  const [open, setOpen] = useState(!!find);
  const [query, setQuery] = useState(find);
  const inputRef = useRef(null);

  // Запрос сменился снаружи (пришли по ссылке, нажали «Назад») — бар следует за
  // адресом. Подстройка в рендере, а не в эффекте: состояние следует за пропом.
  const [prevFind, setPrevFind] = useState(find);
  if (prevFind !== find) {
    setPrevFind(find);
    setQuery(find);
    if (find) setOpen(true);
  }

  const matches = useFindMatches({ rootRef, query, regex, active: open });

  const commit = useCallback((value) => onCommit?.(value, regex), [onCommit, regex]);

  const close = useCallback(() => {
    setOpen(false);
    setQuery('');
    onCommit?.('', false);
  }, [onCommit]);

  const onQueryChange = useCallback((value) => setQuery(value), []);

  // Ctrl+F открывает бар и в файле — искать по открытому файлу, а не по всему
  // интерфейсу вокруг него; Escape его закрывает, как и в модалке. И то и другое
  // молчит, пока поверх открыт диалог или поповер: этими клавишами закрывают и
  // ищут в том, что видно, а видно сейчас не файл.
  const onKey = useEffectEvent((e) => {
    if (hasOverlay()) return;
    if (e.key === 'Escape') {
      if (open) close();
      return;
    }
    e.preventDefault();
    setOpen(true);
    if (open) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  });

  // Перехват, а не всплытие: свои слушатели оверлеи вешают на всплытие, и к
  // моменту, когда очередь дошла бы до нас, меню от этого же Escape уже
  // закрылось — hasOverlay() ответил бы «чисто», и бар закрылся бы заодно с ним.
  useEffect(() => {
    const onKeyDown = (e) => {
      if (e.key === 'Escape') {
        onKey(e);
        return;
      }
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      // e.code — физическая клавиша: на нелатинской раскладке e.key даёт «а».
      if (e.key !== 'f' && e.key !== 'F' && e.code !== 'KeyF') return;
      onKey(e);
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, []);

  return {
    open,
    query,
    total: matches.total,
    activeIndex: matches.activeIndex,
    inputRef,
    onQueryChange,
    onCommitQuery: () => commit(query),
    close,
    goPrev: matches.goPrev,
    goNext: matches.goNext,
  };
}
