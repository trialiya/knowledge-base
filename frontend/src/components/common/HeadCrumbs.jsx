import React, { useLayoutEffect, useRef } from 'react';
import { IconChevronRight } from '../../icons';

/**
 * Хлебные крошки в шапке центра (.workspace__head) — общие для разделов: и путь
 * к открытому файлу, и путь к узлу базы знаний это одна и та же строка чуть
 * ниже по контрасту, чем имя объекта, с разделителями между звеньями.
 *
 * props:
 *   items       — [{ key, label, onNavigate? }]; звено без onNavigate рисуется
 *                 как текущее (не кнопка) — идти по нему некуда. Звено с
 *                 `ellipsis: true` (см. utils/breadcrumbs.collapseCrumbs) —
 *                 схлопнутая середина пути: приглушённое «…», не кнопка и не
 *                 текущее, `title` в нём — имена скрытых звеньев через « / ».
 *   trailingSep — разделитель после последнего звена. Нужен, когда следом в той
 *                 же строке стоит имя открытого объекта (база знаний: крошки —
 *                 только предки), и не нужен, когда последнее звено само и есть
 *                 это имя (файлы).
 *   label       — aria-label для <nav> (это ориентир, у него должно быть имя)
 *
 * Переполнение: строка одна, крошки не переносятся (перенос растил бы шапку и
 * ломал равенство высот колонок) — длинный путь уезжает в горизонтальный
 * скролл. Скролл держим в конце: значим конец пути — открытый объект и его
 * ближайшая папка, а корень и так виден в дереве слева. Без этого глубокий путь
 * показывал бы одно начало, обрывая строку ровно на самой полезной крошке.
 */
const HeadCrumbs = ({ items, trailingSep = false, label }) => {
  const ref = useRef(null);

  // Прокрутка к концу — в layout-эффекте: до отрисовки кадра, иначе на смене
  // файла видно, как путь дёргается от начала к концу.
  const lastKey = items.length ? items[items.length - 1].key : null;
  useLayoutEffect(() => {
    const el = ref.current;
    if (el) el.scrollLeft = el.scrollWidth;
  }, [lastKey]);

  if (items.length === 0) return null;

  return (
    <nav ref={ref} className="workspace__head-crumbs" aria-label={label}>
      {items.map((item, i) => (
        <React.Fragment key={item.key}>
          {item.ellipsis ? (
            <span className="workspace__head-crumb workspace__head-crumb--ellipsis" title={item.title}>
              {item.label}
            </span>
          ) : item.onNavigate ? (
            <button type="button" className="workspace__head-crumb" onClick={item.onNavigate}>
              {item.label}
            </button>
          ) : (
            <span className="workspace__head-crumb workspace__head-crumb--current">{item.label}</span>
          )}
          {(trailingSep || i < items.length - 1) && (
            <span className="workspace__head-crumb-sep" aria-hidden="true">
              <IconChevronRight size={11} />
            </span>
          )}
        </React.Fragment>
      ))}
    </nav>
  );
};

export default HeadCrumbs;
