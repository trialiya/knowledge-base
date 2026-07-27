/**
 * Схлопывает середину длинной цепочки крошек в один разделитель «…», чтобы у
 * очень длинного пути были видны начало и открытый элемент одновременно —
 * без этого приходилось бы либо растягивать шапку, либо прятать начало за
 * горизонтальным скроллом (см. HeadCrumbs).
 *
 * Решение «схлопывать или нет» принимает HeadCrumbs — по факту переполнения
 * строки, а не по числу звеньев: одна и та же глубина в файлах (короткие
 * сегменты пути) помещается, а в базе знаний (длинные названия папок) — нет.
 * Здесь только сама подстановка.
 *
 * items — тот же формат, что принимает HeadCrumbs: [{ key, label, onNavigate? }]
 * keepStart/keepEnd — сколько звеньев с каждого края оставить нетронутыми.
 * Прятать нечего — возвращается ровно та же цепочка (HeadCrumbs полагается на
 * это: схлопывать пустую середину значит терять звено-ссылку задаром).
 */
export function collapseCrumbs(items, keepStart = 1, keepEnd = 1) {
  const hidden = items.slice(keepStart, items.length - keepEnd);
  if (hidden.length === 0) return items;

  return [
    ...items.slice(0, keepStart),
    { key: '__ellipsis__', label: '…', ellipsis: true, title: hidden.map((item) => item.label).join(' / ') },
    ...items.slice(items.length - keepEnd),
  ];
}
