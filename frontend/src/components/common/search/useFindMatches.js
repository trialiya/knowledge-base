import { useEffect, useEffectEvent, useState } from 'react';
import { matchKey, scrollRangeIntoView } from './findMatches';
import useMatchRanges from './useMatchRanges';
import useMatchHighlight from './useMatchHighlight';

/** Начинается ли совпадение не раньше элемента (сам элемент считается). */
function startsAtOrAfter(range, element) {
  const position = element.compareDocumentPosition(range.startContainer);
  return position === 0 || (position & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

/**
 * Индекс первого совпадения на отрезке [from, to): не раньше `from` и, если `to`
 * задан, раньше него; -1, если на отрезке совпадений нет. Отрезок, а не «всё
 * после from»: у раздела без отрендеренных совпадений (нашлось в адресе ссылки
 * или в комментарии, которых в превью нет) первое совпадение после заголовка
 * лежало бы в чужом разделе ниже.
 */
function firstMatchWithin(matches, { from, to }) {
  return matches.findIndex((range) => startsAtOrAfter(range, from) && !(to && startsAtOrAfter(range, to)));
}

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
 * @param anchor  откуда начать: непрозрачное значение (у документа — путь
 *                раздела из адреса), пусто — с первого совпадения
 * @param resolveAnchor (root, anchor) → отрезок области поиска `{ from, to }`
 *                (элементы; `to` null — до конца), внутри которого начать, или
 *                null, когда якоря там нет
 */
export default function useFindMatches({ rootRef, query, regex = false, active = true, anchor = '', resolveAnchor }) {
  const matches = useMatchRanges({ rootRef, query, regex, active });
  const publish = useMatchHighlight();
  const [index, setIndex] = useState(0);
  // Якорь ещё не применён: совпадения к моменту первого пересбора могут быть
  // пусты (содержимое доезжает), поэтому ждём непустого набора, а не одного
  // прохода. Шаг стрелкой снимает ожидание — человек уже выбрал сам.
  const [seeking, setSeeking] = useState(!!anchor);

  // Новый запрос или якорь — снова с первого совпадения (или с якоря).
  // Подстройка в рендере, а не в эффекте: это состояние, следующее за пропом
  // (см. frontend-ui.md).
  const key = matchKey(query, regex, active) + (active ? anchor : '');
  const [prevKey, setPrevKey] = useState(key);
  if (prevKey !== key) {
    setPrevKey(key);
    setIndex(0);
    setSeeking(!!anchor);
  }

  const total = matches.length;
  // Пока якорь не применён, активного совпадения нет: иначе коммит, в котором
  // совпадения появились, подсветил бы и прокрутил к первому, а следующий —
  // к якорю. Ожидание длится ровно один коммит: seekAnchor снимает его ниже.
  const activeIndex = total && !seeking ? Math.min(index, total - 1) : -1;

  // Совпадения живут в DOM, и якорь — тоже: искать его раньше эффекта неоткуда.
  // useEffectEvent, потому что читать rootRef и ставить состояние в самом
  // эффекте нельзя (см. frontend-ui.md), а пересбор совпадений — событие.
  const seekAnchor = useEffectEvent(() => {
    if (!seeking || !total) return;
    const root = rootRef.current;
    const section = root && resolveAnchor ? resolveAnchor(root, anchor) : null;
    const at = section ? firstMatchWithin(matches, section) : -1;
    // Раздела в превью нет или совпадений в нём нет: остаёмся на первом — это
    // лучше, чем прокрутка к заголовку, спорящая с прокруткой к совпадению.
    setIndex(at >= 0 ? at : 0);
    setSeeking(false);
  });

  // И по пересбору, и по новому ожиданию: другой раздел при том же запросе
  // совпадений не пересобирает (запрос в их ключе тот же), а якорь искать надо.
  useEffect(() => {
    seekAnchor();
  }, [matches, seeking]);

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

  const step = (delta) => {
    setSeeking(false);
    // Шагаем от ПОКАЗАННОГО совпадения, а не от сырого index: после пересбора,
    // который нашёл меньше (переключили markdown, diff, догрузилось содержимое),
    // они расходятся — и стрелка прыгала бы не с того, что видно на экране.
    // Прижимаем внутри обновления, а не снаружи: два шага в одном батче обязаны
    // дать два шага, а не схлопнуться в один.
    setIndex((i) => (total ? (Math.min(i, total - 1) + delta + total) % total : 0));
  };

  return {
    total,
    activeIndex,
    goNext: () => step(1),
    goPrev: () => step(-1),
  };
}
