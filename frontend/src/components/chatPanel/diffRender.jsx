import './styles/diff.css';

// Кусочки отрисовки unified diff, общие для блока изменений под ответом ИИ
// (FileChangeBlock) и для режима «Обзор» в модалке вызова инструмента
// (resultViews/DiffResultView). Одна раскраска на оба места — иначе +/− в чате
// и в модалке начинают расходиться цветом.

// Шапка патча — не изменение: без этой ветки `---`/`+++` покрасились бы как
// удалённая и добавленная строка, хотя это имена файлов.
const META =
  /^(diff --git |index |--- |\+\+\+ |new file |deleted file |old mode |new mode |similarity |rename |copy |Binary files )/;

/**
 * Классы строк патча.
 *
 * Шапка распознаётся только ДО первого `@@`: внутри ханка каждая строка —
 * содержимое файла со своим знаком, и удаление строки, начинающейся с `-- `,
 * даёт `--- `, которое иначе покрасилось бы шапкой вместо красного.
 */
const lineClasses = (lines) => {
  let inHunk = false;

  return lines.map((line) => {
    if (line.startsWith('@@')) {
      inHunk = true;
      return 'diff-line diff-line--hunk';
    }
    // Патч на несколько файлов: со следующего `diff --git` снова идёт шапка.
    if (line.startsWith('diff --git ')) inHunk = false;

    if (!inHunk && META.test(line)) return 'diff-line diff-line--meta';
    if (line.startsWith('+')) return 'diff-line diff-line--add';
    if (line.startsWith('-')) return 'diff-line diff-line--del';
    return 'diff-line';
  });
};

/**
 * Строки unified diff. Возвращает только сами строки — родительский `<pre>`
 * (моноширинный шрифт, фон, скролл по горизонтали) на вызывающем: в чате это
 * `.fcd-diff`, в модалке вызова — `.tool-diff__patch`.
 */
export const DiffLines = ({ patch }) => {
  const lines = patch.split('\n');
  const classes = lineClasses(lines);

  return lines.map((line, i) => (
    // Индекс как key безопасен: текст diff'а иммутабелен в рамках открытой модалки.

    <span key={i} className={classes[i]}>
      {line}
      {'\n'}
    </span>
  ));
};

/** Счётчики строк `+N/−M`. */
export const DiffStats = ({ additions, deletions }) => (
  <span className="diff-stats">
    <span className="diff-stats__add">+{additions}</span>/<span className="diff-stats__del">−{deletions}</span>
  </span>
);
