/**
 * Куда прокрутить контейнер дерева, чтобы выбранная строка была видна.
 *
 * Чистая арифметика над прямоугольниками — вся проверка живёт в
 * `treeScroll.test.js`, а не в браузере.
 *
 * Строка дерева растянута на всю ширину раскрытого дерева
 * (`.file-tree { width: max-content }`, шире панели), поэтому ни
 * `scrollIntoView` на ней, ни фокус без `preventScroll` не годятся: для
 * элемента шире вьюпорта «nearest» означает «прижать к начальному краю», то
 * есть сбросить горизонтальную прокрутку в ноль. Видимой должна быть не вся
 * строка, а её содержательная часть — от шеврона до конца имени.
 *
 * @param {DOMRectReadOnly} view видимая область контейнера
 * @param {DOMRectReadOnly} row строка целиком
 * @param {DOMRectReadOnly} start первый значимый элемент строки (шеврон)
 * @param {DOMRectReadOnly} end последний значимый элемент строки (метка)
 * @param {{ top: number, left: number }} scroll текущие scrollTop/scrollLeft
 * @returns {{ top: number, left: number }} куда их поставить
 */
export default function revealRow(view, row, start, end, scroll) {
  let { top, left } = scroll;

  // Вертикаль: строка целиком должна попасть в видимую область.
  if (row.bottom > view.bottom) top += row.bottom - view.bottom;
  else if (row.top < view.top) top -= view.top - row.top;

  // Горизонталь: минимальный сдвиг, чтобы уместились и шеврон, и имя. Когда
  // они вместе шире панели, уместить всё нельзя — тогда показываем начало, а
  // не хвост: имя читают слева направо, и по «…RepositoryIntegrationTest.java»
  // не понять, какой это файл, тогда как по началу — понять можно.
  const tooWide = end.right - start.left > view.right - view.left;
  if (tooWide || start.left < view.left) left += start.left - view.left;
  else if (end.right > view.right) left += end.right - view.right;

  return { top, left };
}
