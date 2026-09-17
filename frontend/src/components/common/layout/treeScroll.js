/**
 * Доводка прокрутки до выбранной строки левой панели (`.ws-item`).
 *
 * Арифметика здесь чистая и проверяется на числах (`treeScroll.test.js`), а не
 * в браузере; с DOM говорит только `clientBox`.
 *
 * Готового способа в браузере нет. `scrollIntoView` и фокус без
 * `preventScroll` двигают обе оси, а строка дерева файлов растянута на всю
 * ширину раскрытого дерева (`.file-tree { width: max-content }`, шире панели):
 * для элемента шире вьюпорта «nearest» означает «прижать к начальному краю», то
 * есть сбросить горизонтальную прокрутку в ноль.
 *
 * Отсюда два входа. Дереву базы знаний и клавиатурной навигации нужна только
 * вертикаль (`revealVertically`): горизонтальной прокрутки у их панелей нет,
 * имя обрезается многоточием. Дереву файлов нужны обе оси (`revealRow`).
 *
 * Прямоугольник видимой области везде **клиентский** (`clientBox`), а не рамка
 * элемента: при классических (не накладных) полосах прокрутки их жёлоб входит в
 * рамку, и доводка по ней прятала бы строку под полосой.
 */

/**
 * Видимая область прокручиваемого элемента — в тех же координатах, что и
 * `getBoundingClientRect` строки, но без желобов полос прокрутки.
 *
 * @param {Element} el прокручиваемый контейнер
 * @returns {{top: number, bottom: number, left: number, right: number}}
 */
export function clientBox(el) {
  const box = el.getBoundingClientRect();
  const left = box.left + el.clientLeft;
  const top = box.top + el.clientTop;
  return { left, top, right: left + el.clientWidth, bottom: top + el.clientHeight };
}

/**
 * @param {{top: number, bottom: number}} view видимая область контейнера
 * @param {{top: number, bottom: number}} row строка
 * @param {number} top текущий scrollTop
 * @returns {number} куда его поставить
 */
export function revealVertically(view, row, top) {
  if (row.bottom > view.bottom) return top + (row.bottom - view.bottom);
  if (row.top < view.top) return top - (view.top - row.top);
  return top;
}

/**
 * То же плюс горизонталь — для дерева, которое шире своей панели.
 *
 * Видимой по горизонтали должна быть не вся растянутая строка, а её
 * содержательная часть: от шеврона до конца имени.
 *
 * @param {{top: number, bottom: number, left: number, right: number}} view видимая область контейнера
 * @param {{top: number, bottom: number}} row строка целиком
 * @param {{left: number}} start первый значимый элемент строки (шеврон)
 * @param {{right: number}} end последний значимый элемент строки (метка)
 * @param {{ top: number, left: number }} scroll текущие scrollTop/scrollLeft
 * @returns {{ top: number, left: number }} куда их поставить
 */
export default function revealRow(view, row, start, end, scroll) {
  let { left } = scroll;

  // Минимальный сдвиг, чтобы уместились и шеврон, и имя. Когда они вместе шире
  // панели, уместить всё нельзя — тогда показываем начало, а не хвост: имя
  // читают слева направо, и по «…RepositoryIntegrationTest.java» не понять,
  // какой это файл, тогда как по началу — понять можно.
  const tooWide = end.right - start.left > view.right - view.left;
  if (tooWide || start.left < view.left) left += start.left - view.left;
  else if (end.right > view.right) left += end.right - view.right;

  return { top: revealVertically(view, row, scroll.top), left };
}
