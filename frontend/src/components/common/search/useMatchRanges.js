import { useEffect, useEffectEvent, useState } from 'react';
import { buildMatcher, collectMatchRanges, matchKey, NO_RANGES } from './findMatches';

// Содержимое меняется само (догрузка превью, diff, переключение markdown, приход
// сообщения) — пересобираем совпадения по MutationObserver, склеивая пачку
// правок одним таймером.
const RECOLLECT_MS = 120;

// Собственная перерисовка бара — не изменение содержимого, по которому ищем.
const isInBar = (node) => {
  const el = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return !!el?.closest?.('[data-find-bar]');
};

/**
 * Range'и вхождений запроса в поддереве, пересобираемые при изменении
 * содержимого. Что с ними делать — подсветить, посчитать, прокрутить — решает
 * вызывающий: у файла и модалки это ходьба по совпадениям (useFindMatches), у
 * ленты чата — только подсветка, а список совпадений там серверный.
 *
 * @param rootRef ref на элемент — область поиска
 * @param query   что искать; пусто — совпадений нет
 * @param regex   трактовать запрос как регулярное выражение
 * @param within  селектор блоков, которыми ограничена область (см. collectMatchRanges)
 * @param active  включён ли поиск вообще (закрытый бар совпадений не держит)
 */
export default function useMatchRanges({ rootRef, query, regex = false, within, active = true }) {
  const [ranges, setRanges] = useState(NO_RANGES);
  const key = matchKey(query, regex, active);

  // Пересбор — useEffectEvent: совпадения живут в DOM, а его до коммита нет, и
  // взять их раньше эффекта неоткуда; при этом пересобрать надо и по смене
  // запроса, и по изменению содержимого, у которых разные зависимости.
  const collect = useEffectEvent(() => {
    const root = rootRef.current;
    const matcher = active ? buildMatcher(query, regex) : null;
    setRanges(root && matcher ? collectMatchRanges(root, matcher, { within }) : NO_RANGES);
  });

  useEffect(() => {
    collect();
  }, [key]);

  // Содержимое поменялось — старые Range указывают на выброшенные узлы.
  useEffect(() => {
    const root = rootRef.current;
    if (!key || !root || typeof MutationObserver === 'undefined') return undefined;
    let timer = null;
    const observer = new MutationObserver((records) => {
      if (records.every((r) => isInBar(r.target))) return;
      clearTimeout(timer);
      timer = setTimeout(collect, RECOLLECT_MS);
    });
    observer.observe(root, { childList: true, subtree: true, characterData: true });
    return () => {
      clearTimeout(timer);
      observer.disconnect();
    };
  }, [rootRef, key]);

  return ranges;
}
