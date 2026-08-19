// Что режим «Обзор» показывает для формы «совпадения поиска по содержимому»
// (`grepContent`): блоки строк с настоящими номерами, сгруппированные по файлу.
//
// Разбор — по форме, а не по имени инструмента (см. registry.js). Признак
// формы: у каждого элемента путь, номер строки совпадения и текст блока.
//
// Текст блока уже размечен бэкендом в формате `git grep -C` (см. javadoc
// GitGrepMatch): `:N:строка` — совпадение, `-N-строка` — контекст. Нумерация
// уже внутри текста — именно поэтому форму нельзя показывать текстовым видом,
// он нарисовал бы поверх ещё один столбец номеров, считающий от единицы.

const MAX_MATCHES = 300;

/** `:85:  bar();` / `-84-  foo();` → номер строки, признак совпадения, текст. */
const MARKED_LINE = /^([:-])(\d+)\1(.*)$/;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

const isMatchRecord = (obj) =>
  isPlainObject(obj) &&
  typeof obj.path === 'string' &&
  !!obj.path &&
  Number.isInteger(obj.matchLine) &&
  typeof obj.text === 'string';

/**
 * Текст блока → строки с номерами.
 *
 * Без контекста (`contextLines=0`) разметки в тексте нет вовсе — это просто
 * сама строка совпадения, и номер берётся из `matchLine`.
 */
const toLines = (text, matchLine) =>
  text
    .replace(/\n$/, '')
    .split('\n')
    .map((line, i) => {
      const marked = MARKED_LINE.exec(line);
      if (marked) {
        return { no: Number(marked[2]), match: marked[1] === ':', text: marked[3] };
      }
      // Неразмеченная строка: номер известен только у первой, дальше считаем
      // подряд — врать на единицу лучше, чем не показать номер вовсе.
      return { no: matchLine + i, match: i === 0, text: line };
    });

/**
 * Разобранный ответ вызова → файлы с блоками для `<GrepMatchesView>`, либо null.
 *
 * Группировка по файлу — порядком появления: git grep отдаёт блоки одного файла
 * подряд, но полагаться на это не нужно, а порядок выдачи сохранить стоит.
 */
export const detectGrepMatches = ({ parsed, isJson }) => {
  if (!isJson || !Array.isArray(parsed)) return null;
  if (parsed.length === 0 || parsed.length > MAX_MATCHES) return null;
  if (!parsed.every(isMatchRecord)) return null;

  const byPath = new Map();
  parsed.forEach((match, i) => {
    const block = { key: `block-${i}`, lines: toLines(match.text, match.matchLine) };
    const file = byPath.get(match.path);
    if (file) file.blocks.push(block);
    else byPath.set(match.path, { key: `file-${i}`, path: match.path, blocks: [block] });
  });

  // Проект у всех совпадений один — вызов ищет в одном репозитории, — но брать
  // его надо из ответа, а не из проекта чата: у grepContent есть аргумент
  // project, и вызов мог искать в соседнем.
  const project = parsed.find((match) => match.project)?.project ?? null;

  return { files: [...byPath.values()], matches: parsed.length, project };
};
