/**
 * Раскладка ханков blame по строкам таблицы кода: одна ячейка на ханк, растянутая
 * `rowSpan`'ом на его строки, — так подпись стоит на первой строке диапазона, а
 * выравнивание с текстом держит сама таблица.
 *
 * @param hunks диапазоны из ответа `/files/blame`, по порядку строк
 * @param fromLine номер первой показанной строки (1-based)
 * @param count сколько строк показано
 * @returns массив на строку: `{ hunk, span }` — здесь начинается ячейка (`hunk`
 *   null — строка вне ханков: ответа ещё нет или строк в нём меньше), `null` —
 *   строка накрыта ячейкой выше
 */
export function blameRows(hunks, fromLine, count) {
  const rows = new Array(count).fill(undefined);
  const last = fromLine + count - 1;
  for (const hunk of hunks) {
    const start = Math.max(hunk.fromLine, fromLine);
    const end = Math.min(hunk.fromLine + hunk.lineCount - 1, last);
    if (end < start) continue;
    rows[start - fromLine] = { hunk, span: end - start + 1 };
    for (let line = start + 1; line <= end; line++) rows[line - fromLine] = null;
  }
  // Строки, до которых ханки не дотянулись, получают пустую ячейку по одной на
  // строку: без неё таблица сдвинула бы номер и текст влево.
  for (let i = 0; i < count; i++) {
    if (rows[i] === undefined) rows[i] = { hunk: null, span: 1 };
  }
  return rows;
}
