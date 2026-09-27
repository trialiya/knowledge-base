/**
 * Языки, для которых бэкенд строит структуру файла: тот же набор, что
 * `OutlineService.SUPPORTED_LANGUAGES`, — там источник правды. Язык файла
 * называет бэкенд (`file.language`), и вкладка есть только у этих: у прочих
 * запрос ответил бы 400.
 */
export const OUTLINE_LANGUAGES = new Set(['java', 'javascript', 'typescript', 'python', 'sql', 'markdown']);

export const MARKDOWN = 'markdown';
export const PREAMBLE = 'preamble';

/**
 * Символы структуры → строки списка с глубиной вложенности.
 *
 * Вложенность — по диапазону строк, а не по виду символа: метод внутри класса
 * потому, что его строки внутри строк класса, и так же подраздел markdown
 * внутри раздела (бэкенд отдаёт у раздела диапазон всего поддерева). Одно
 * правило на все языки — и у markdown заодно правильная глубина при пропуске
 * уровня (`#`, затем сразу `###`) и у файла, начинающегося с `##`.
 *
 * Символ в одну строку ничего содержать не может: без этой проверки два поля
 * на одной строке вложились бы друг в друга. Преамбула — текст до первого
 * заголовка — тоже ничего не содержит, это не раздел.
 *
 * @param {Array<{kind: string, startLine: number, endLine: number}>} symbols в порядке файла
 * @returns {Array<{symbol: object, depth: number}>}
 */
export function outlineRows(symbols) {
  const open = [];
  return symbols.map((symbol) => {
    while (open.length && open[open.length - 1] < symbol.startLine) open.pop();
    const depth = open.length;
    if (symbol.endLine > symbol.startLine && symbol.kind !== PREAMBLE) open.push(symbol.endLine);
    return { symbol, depth };
  });
}
