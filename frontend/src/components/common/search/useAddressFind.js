import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react';
import useFindMatches from './useFindMatches';
import { hasOpenModal, hasOverlay } from '@/components/common/layout/overlayStack';
import { isFindShortcut, isTypingTarget } from './findShortcut';

/**
 * Find-бар над открытым файлом или документом: то же, что Ctrl+F в модалке, но
 * запрос приносит адрес.
 *
 * Переход из единого поиска кладёт в адрес `find` (у файла — и `re=1`, если
 * искали регуляркой; у документа — `section`, раздел, где нашлось) — бар
 * открывается сам, с подставленным запросом, и центр проматывается к первому
 * совпадению. Иначе клик по найденному открывал бы файл на первой строке, и
 * совпадение пришлось бы искать заново глазами.
 *
 * Набранное в поле живёт в локальном черновике, а в адрес уходит по Enter, уходу
 * фокуса и закрытию бара — как в фильтрах поиска: подсветка обязана идти за
 * каждой буквой, а вот history.replaceState на каждую букву браузеры считают
 * злоупотреблением (Safari — с ошибкой).
 *
 * @param rootRef  ref на прокручиваемое тело — область поиска
 * @param find     запрос из адреса ('' — открыли не из поиска)
 * @param regex    читать ли запрос как регулярное выражение
 * @param anchor   откуда начать (см. useFindMatches): раздел из адреса у документа
 * @param resolveAnchor (root, anchor) → элемент якоря в области поиска
 * @param active   слушать ли Ctrl+F и Escape: раздел, смонтированный, но скрытый
 *                 (база знаний под вкладкой чата), перехватывал бы чужой поиск
 * @param onCommit (find, regex) — записать запрос в адрес
 */
export default function useAddressFind({
  rootRef,
  find,
  regex = false,
  anchor = '',
  resolveAnchor,
  active = true,
  onCommit,
}) {
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

  // Якорь относится к запросу из адреса: набранный поверх него другой запрос
  // начинает с первого совпадения, как везде.
  const matches = useFindMatches({
    rootRef,
    query,
    regex,
    active: open,
    anchor: query === find ? anchor : '',
    resolveAnchor,
  });

  const commit = useCallback((value) => onCommit?.(value, regex), [onCommit, regex]);

  const close = useCallback(() => {
    setOpen(false);
    setQuery('');
    onCommit?.('', false);
  }, [onCommit]);

  const onQueryChange = useCallback((value) => setQuery(value), []);

  // Ctrl+F открывает бар и здесь — искать по открытому файлу, а не по всему
  // интерфейсу вокруг него; Escape его закрывает, как и в модалке. Условия у них
  // разные, и намеренно. Escape уступает любому оверлею (им закрывают верхнее, а
  // верхнее сейчас — диалог или поповер) и любому полю ввода (см. isTypingTarget:
  // слушатель на перехвате отобрал бы Escape у того, кто ждёт его на всплытии).
  // Ctrl+F уступает только диалогу, у которого есть свой бар; поповер (меню git,
  // выбор ревизии) искать не умеет, и уступив ему, мы отдали бы нажатие
  // браузерному поиску по всей странице.
  const onKey = useEffectEvent((e) => {
    if (e.key === 'Escape') {
      if (hasOverlay() || isTypingTarget(e)) return;
      if (open) close();
      return;
    }
    if (hasOpenModal()) return;
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
    if (!active) return undefined;
    const onKeyDown = (e) => {
      if (e.key === 'Escape' || isFindShortcut(e)) onKey(e);
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [active]);

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
