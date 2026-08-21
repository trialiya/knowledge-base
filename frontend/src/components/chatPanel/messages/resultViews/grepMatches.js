// Что режим «Обзор» показывает для формы «совпадения поиска по содержимому»
// (`grepContent` по репозиторию, `grepDocuments` по базе знаний): блоки строк с
// настоящими номерами, сгруппированные по источнику.
//
// Разбор — по форме, а не по имени инструмента (см. registry.js). Признак
// формы: у каждого элемента номер строки совпадения, текст блока и то, откуда
// он взят, — путь файла либо id и заголовок документа.
//
// Текст блока уже размечен бэкендом в формате `git grep -C` (см. javadoc
// GitGrepMatch): `:N:строка` — совпадение, `-N-строка` — контекст. Нумерация
// уже внутри текста — именно поэтому форму нельзя показывать текстовым видом,
// он нарисовал бы поверх ещё один столбец номеров, считающий от единицы.

const MAX_MATCHES = 300;

/** `:85:  bar();` / `-84-  foo();` → номер строки, признак совпадения, текст. */
const MARKED_LINE = /^([:-])(\d+)\1(.*)$/;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

/** Источник блока: файл репозитория (путь) или документ базы знаний (id + заголовок). */
const sourceOf = (obj) => {
  if (typeof obj.path === 'string' && obj.path) return { key: obj.path, label: obj.path };
  if (typeof obj.documentId === 'number' && typeof obj.title === 'string' && obj.title) {
    return { key: `doc:${obj.documentId}`, label: obj.title };
  }
  return null;
};

const isMatchRecord = (obj) =>
  isPlainObject(obj) && !!sourceOf(obj) && Number.isInteger(obj.matchLine) && typeof obj.text === 'string';

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
 * Разобранный ответ вызова → источники с блоками для `<GrepMatchesView>`, либо null.
 *
 * Группировка — порядком появления: и git grep, и `grepDocuments` отдают блоки
 * одного источника подряд, но полагаться на это не нужно, а порядок выдачи
 * сохранить стоит. Поле `path` у источника — то, чем он подписан: путь файла или
 * заголовок документа.
 */
export const detectGrepMatches = ({ parsed, isJson }) => {
  if (!isJson || !Array.isArray(parsed)) return null;
  if (parsed.length === 0 || parsed.length > MAX_MATCHES) return null;
  if (!parsed.every(isMatchRecord)) return null;

  const bySource = new Map();
  parsed.forEach((match, i) => {
    const source = sourceOf(match);
    const block = { key: `block-${i}`, lines: toLines(match.text, match.matchLine) };
    const found = bySource.get(source.key);
    if (found) found.blocks.push(block);
    else bySource.set(source.key, { key: `file-${i}`, path: source.label, blocks: [block] });
  });

  // Проект у всех совпадений один — вызов ищет в одном репозитории, — но брать
  // его надо из ответа, а не из проекта чата: у grepContent есть аргумент
  // project, и вызов мог искать в соседнем.
  const project = parsed.find((match) => match.project)?.project ?? null;

  return { files: [...bySource.values()], matches: parsed.length, project };
};
