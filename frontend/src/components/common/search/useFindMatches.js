import { useEffect, useState } from 'react';
import { matchKey, scrollRangeIntoView } from './findMatches';
import useMatchRanges from './useMatchRanges';
import useMatchHighlight from './useMatchHighlight';

/**
 * Совпадения запроса в поддереве, их подсветка и переход между ними.
 *
 * Запрос и «включён ли поиск» приходят снаружи: у модалки их держит find-бар,
 * у открытого файла — адрес. Хук отвечает только за то, что из этого следует —
 * какие Range нашлись, какой из них активен, куда прокрутить.
 *
 * Активное совпадение считается на чтении, а не хранится: после пересбора (файл
 * догрузился, переключили diff) прежний индекс может выйти за границы, и
 * отдельное состояние пришлось бы чинить эффектом.
 *
 * Лента чата этим хуком не пользуется: список совпадений у неё серверный
 * (история пагинирована, найтись должно и незагруженное), поэтому она берёт
 * только слои под ним — useMatchRanges и useMatchHighlight.
 *
 * @param rootRef ref на элемент — область поиска
 * @param query   что искать; пусто — совпадений нет
 * @param regex   трактовать запрос как регулярное выражение
 * @param active  включён ли поиск вообще (закрытый бар совпадений не держит)
 */
export default function useFindMatches({ rootRef, query, regex = false, active = true }) {
  const matches = useMatchRanges({ rootRef, query, regex, active });
  const publish = useMatchHighlight();
  const [index, setIndex] = useState(0);

  // Новый запрос — снова с первого совпадения. Подстройка в рендере, а не в
  // эффекте: это состояние, следующее за пропом (см. frontend-ui.md).
  const key = matchKey(query, regex, active);
  const [prevKey, setPrevKey] = useState(key);
  if (prevKey !== key) {
    setPrevKey(key);
    setIndex(0);
  }

  const total = matches.length;
  const activeIndex = total ? Math.min(index, total - 1) : -1;

  // Подсветка: активное совпадение — отдельным, более контрастным стилем.
  useEffect(() => {
    publish(
      matches.filter((_, i) => i !== activeIndex),
      activeIndex >= 0 ? [matches[activeIndex]] : [],
    );
  }, [publish, matches, activeIndex]);

  useEffect(() => {
    const root = rootRef.current;
    const range = activeIndex >= 0 ? matches[activeIndex] : null;
    if (root && range) scrollRangeIntoView(range, root);
  }, [rootRef, matches, activeIndex]);

  return {
    total,
    activeIndex,
    // Шагаем от ПОКАЗАННОГО совпадения, а не от сырого index: после пересбора,
    // который нашёл меньше (переключили markdown, diff, догрузилось содержимое),
    // они расходятся — и стрелка прыгала бы не с того, что видно на экране.
    // Прижимаем внутри обновления, а не снаружи: два шага в одном батче обязаны
    // дать два шага, а не схлопнуться в один.
    goNext: () => setIndex((i) => (total ? (Math.min(i, total - 1) + 1) % total : 0)),
    goPrev: () => setIndex((i) => (total ? (Math.min(i, total - 1) - 1 + total) % total : 0)),
  };
}
