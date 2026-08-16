import './styles/diff.css';

// Кусочки отрисовки unified diff, общие для блока изменений под ответом ИИ
// (FileChangeBlock) и для режима «Обзор» в модалке вызова инструмента
// (resultViews/DiffResultView). Одна раскраска на оба места — иначе +/− в чате
// и в модалке начинают расходиться цветом.

// Шапка патча — не изменение: без этой ветки `---`/`+++` покрасились бы как
// удалённая и добавленная строка, хотя это имена файлов.
const META =
  /^(diff --git |index |--- |\+\+\+ |new file |deleted file |old mode |new mode |similarity |rename |copy |Binary files )/;

const lineClass = (line) => {
  if (META.test(line)) return 'diff-line diff-line--meta';
  if (line.startsWith('+')) return 'diff-line diff-line--add';
  if (line.startsWith('-')) return 'diff-line diff-line--del';
  if (line.startsWith('@@')) return 'diff-line diff-line--hunk';
  return 'diff-line';
};

/**
 * Строки unified diff. Возвращает только сами строки — родительский `<pre>`
 * (моноширинный шрифт, фон, скролл по горизонтали) на вызывающем: в чате это
 * `.fcd-diff`, в модалке вызова — `.tool-diff__patch`.
 */
export const DiffLines = ({ patch }) =>
  patch.split('\n').map((line, i) => (
    // Индекс как key безопасен: текст diff'а иммутабелен в рамках открытой модалки.

    <span key={i} className={lineClass(line)}>
      {line}
      {'\n'}
    </span>
  ));

/** Счётчики строк `+N/−M`. */
export const DiffStats = ({ additions, deletions }) => (
  <span className="diff-stats">
    <span className="diff-stats__add">+{additions}</span>/<span className="diff-stats__del">−{deletions}</span>
  </span>
);
