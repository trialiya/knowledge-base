/**
 * Скрипт, которым открывается пробный запуск в «Настройках → Скрипты».
 *
 * Живёт отдельным модулем, а не строкой внутри компонента: это единственный
 * пример kb-API, который пользователь видит вживую, и правится он как код —
 * с подсветкой и без экранирования кавычек в JSX.
 *
 * Что делает: разбирает комментарии одного файла репозитория. Пример выбран
 * так, чтобы за один прогон показать всё, из чего состоит скрипт, — поиск
 * (kb.grep), чтение (kb.read), обычный JS поверх текста, журнал (kb.log) и
 * возврат результата. Путь не зашит: индексируется произвольный репозиторий
 * (kb.git.project-path), поэтому файл ищется по содержимому — иначе «первый
 * файл по алфавиту» легко оказывается тем, в котором комментариев нет, и
 * пример открывается нулями.
 */
const SCRIPT_EXAMPLE = `// Разбираем комментарии в одном файле репозитория.
// kb.grep — чтобы взять файл, в котором комментарии точно есть,
// kb.read — весь его текст, дальше обычный JavaScript.
const hit =
  kb.grep('/**', { glob: '**/*.java', max: 1 })[0] ||
  kb.grep('/**', { glob: '**/*.js', max: 1 })[0] ||
  kb.grep('/**', { glob: '**/*.ts', max: 1 })[0];
const path = hit ? hit.path : kb.files()[0];
if (!path) return { error: 'В репозитории нет отслеживаемых файлов' };

const lines = kb.read(path).split('\\n');
const comments = [];
let block = null; // открытый /* ... */

lines.forEach((raw, i) => {
  const line = raw.trim();

  if (block) {
    const end = line.indexOf('*/');
    block.parts.push((end >= 0 ? line.slice(0, end) : line).replace(/^\\*+/, '').trim());
    if (end >= 0) {
      comments.push({ line: block.line, kind: 'block', text: block.parts.filter(Boolean).join(' ') });
      block = null;
    }
    return;
  }

  if (line.startsWith('/*')) {
    const end = line.indexOf('*/');
    if (end > 1) comments.push({ line: i + 1, kind: 'block', text: line.slice(2, end).trim() });
    // slice(2) снимает '/*', replace — звёздочки javadoc ('/**' и ' * ' в теле).
    else block = { line: i + 1, parts: [line.slice(2).replace(/^\\*+/, '').trim()] };
    return;
  }

  if (line.startsWith('//')) {
    comments.push({ line: i + 1, kind: 'line', text: line.slice(2).trim() });
  }
});

kb.log('Файл: ' + path + ', строк: ' + lines.length);

// return — единственный способ вернуть результат наружу.
return {
  file: path,
  total: comments.length,
  byKind: {
    line: comments.filter((c) => c.kind === 'line').length,
    block: comments.filter((c) => c.kind === 'block').length,
  },
  first: comments.slice(0, 5),
};
`;

export default SCRIPT_EXAMPLE;
