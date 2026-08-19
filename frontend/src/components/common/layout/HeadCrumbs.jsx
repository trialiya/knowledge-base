import { Fragment, useCallback, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronRight } from '../../../icons/index';
import { collapseCrumbs } from '../../../utils/breadcrumbs';

/**
 * Хлебные крошки в шапке центра (.workspace__head) — общие для разделов: и путь
 * к открытому файлу, и путь к узлу базы знаний это одна и та же строка чуть
 * ниже по контрасту, чем имя объекта, с разделителями между звеньями.
 *
 * props:
 *   items       — [{ key, label, onNavigate? }]; звено без onNavigate рисуется
 *                 как текущее (не кнопка) — идти по нему некуда
 *   trailingSep — разделитель после последнего звена. Нужен, когда следом в той
 *                 же строке стоит имя открытого объекта (база знаний: крошки —
 *                 только предки), и не нужен, когда последнее звено само и есть
 *                 это имя (файлы).
 *   label       — aria-label для <nav> (это ориентир, у него должно быть имя)
 *
 * Переполнение: строка одна, крошки не переносятся (перенос растил бы шапку и
 * ломал равенство высот колонок). Не влезающая цепочка схлопывается серединой в
 * одно «…» (utils/breadcrumbs), чтобы одновременно были видны начало пути и его
 * конец. Схлопываем по замеру, а не по числу звеньев: путь из шести коротких
 * сегментов помещается целиком, а из трёх длинных названий — уже нет, и первый
 * трогать незачем. По клику «…» середина раскрывается обратно — промежуточные
 * папки такие же цели навигации, как остальные звенья.
 *
 * Если не влезает и схлопнутая цепочка (длинное имя самого объекта), остаётся
 * горизонтальный скролл, прижатый к концу: значим конец пути — открытый объект и
 * его ближайшая папка, а корень и так виден в дереве слева.
 */
const HeadCrumbs = ({ items, trailingSep = false, label }) => {
  const { t } = useTranslation();
  const ref = useRef(null);
  const [headWidth, setHeadWidth] = useState(0);
  // Решение по одному замеру: для какой пары (путь, ширина шапки) оно принято и
  // что из него вышло. Сменилась пара — решение просто перестаёт подходить, и
  // цепочка снова считается полной; отдельного эффекта-сброса для этого не нужно.
  //   'collapsed' — схлопнули по замеру, 'expanded' — раскрыли кликом по «…».
  const [fit, setFit] = useState(null); // { key, mode } | null

  // Последнее звено — это либо сам открытый объект (файлы), либо ближайшая к
  // нему папка (база знаний: имя узла идёт следом заголовком шапки). И то и
  // другое важнее корня, поэтому у файлов с конца оставляем два звена — папку и
  // файл: иначе от пути остаётся «Repository › … › File.java», где не видно
  // даже, из какого каталога файл открыт.
  const keepEnd = trailingSep ? 1 : 2;

  const chainKey = items.map((item) => item.key).join(' ');
  const measureKey = `${headWidth} ${chainKey}`;
  const mode = fit?.key === measureKey ? fit.mode : null;
  const collapsed = mode === 'collapsed';
  const shown = collapsed ? collapseCrumbs(items, 1, keepEnd) : items;

  // Ширину слушаем у шапки, а не у самой строки крошек: у .workspace__head-crumbs
  // flex-basis auto, то есть её ширина зависит от содержимого, и наблюдение за
  // ней дало бы цикл «схлопнули → сузились → раскрыли обратно».
  useLayoutEffect(() => {
    const head = ref.current?.parentElement;
    if (!head || typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(([entry]) => setHeadWidth(entry.contentRect.width));
    ro.observe(head);
    return () => ro.disconnect();
  }, []);

  // Сам замер — только по развёрнутой цепочке: схлопнутая заведомо уже, и
  // проверка по ней ничего не скажет. +1 — на дробные ширины, округление
  // scrollWidth/clientWidth иначе даёт мнимое переполнение в один пиксель.
  // Без массива зависимостей — эффект обязан перемерять после каждого рендера
  // (не только при смене cхлопнутости), а от бесконечного цикла защищает ранний
  // выход выше: после setFit решение по этому measureKey уже принято.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || mode !== null) return;
    if (el.scrollWidth > el.clientWidth + 1) setFit({ key: measureKey, mode: 'collapsed' });
  });

  // Прокрутка к концу — в layout-эффекте: до отрисовки кадра, иначе на смене
  // файла видно, как путь дёргается от начала к концу.
  const lastKey = items.length ? items[items.length - 1].key : null;
  useLayoutEffect(() => {
    const el = ref.current;
    if (el) el.scrollLeft = el.scrollWidth;
  }, [lastKey, collapsed]);

  const expand = useCallback(() => setFit({ key: measureKey, mode: 'expanded' }), [measureKey]);

  if (items.length === 0) return null;

  return (
    <nav ref={ref} className="workspace__head-crumbs" aria-label={label}>
      {shown.map((item, i) => (
        <Fragment key={item.key}>
          {item.ellipsis ? (
            <button
              type="button"
              className="workspace__head-crumb workspace__head-crumb--ellipsis"
              onClick={expand}
              title={item.title}
              aria-label={t('crumbs.expand')}
            >
              {item.label}
            </button>
          ) : item.onNavigate ? (
            <button type="button" className="workspace__head-crumb" onClick={item.onNavigate}>
              {item.label}
            </button>
          ) : (
            <span className="workspace__head-crumb workspace__head-crumb--current">{item.label}</span>
          )}
          {(trailingSep || i < shown.length - 1) && (
            <span className="workspace__head-crumb-sep" aria-hidden="true">
              <IconChevronRight size={11} />
            </span>
          )}
        </Fragment>
      ))}
    </nav>
  );
};

export default HeadCrumbs;
