/**
 * Схлопывает середину длинной цепочки крошек в один разделитель «…», чтобы у
 * очень длинного пути были видны начало и открытый элемент одновременно —
 * без этого приходилось бы либо растягивать шапку, либо прятать начало за
 * горизонтальным скроллом (см. HeadCrumbs).
 *
 * items — тот же формат, что принимает HeadCrumbs: [{ key, label, onNavigate? }]
 * keepStart/keepEnd — сколько звеньев с каждого края оставить нетронутыми.
 * Схлопывание срабатывает, только если середина прячет от двух звеньев —
 * заменять единственное звено на «…» ничего не выигрывает.
 */
export function collapseCrumbs(items, keepStart = 1, keepEnd = 1) {
  const hidden = items.slice(keepStart, items.length - keepEnd);
  if (hidden.length < 2) return items;

  return [
    ...items.slice(0, keepStart),
    { key: '__ellipsis__', label: '…', ellipsis: true, title: hidden.map((item) => item.label).join(' / ') },
    ...items.slice(items.length - keepEnd),
  ];
}
