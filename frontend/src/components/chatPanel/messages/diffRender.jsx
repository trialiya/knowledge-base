import '../styles/diff.css';

// Кусочки отрисовки unified diff, общие для блока изменений под ответом ИИ
// (FileChangeBlock) и для режима «Обзор» в модалке вызова инструмента
// (resultViews/DiffResultView). Одна раскраска на оба места — иначе +/− в чате
// и в модалке начинают расходиться цветом.

// Шапка патча — не изменение: без этой ветки `---`/`+++` покрасились бы как
// удалённая и добавленная строка, хотя это имена файлов.
const META =
  /^(diff --git |index |--- |\+\+\+ |new file |deleted file |old mode |new mode |similarity |rename |copy |Binary files )/;

/** Заголовок ханка: из него берутся номера первой строки старого и нового файла. */
const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/**
 * Строки патча → класс и номер строки в файле.
 *
 * Шапка распознаётся только ДО первого `@@`: внутри ханка каждая строка —
 * содержимое файла со своим знаком, и удаление строки, начинающейся с `-- `,
 * даёт `--- `, которое иначе покрасилось бы шапкой вместо красного.
 *
 * Номер один на строку, а не пара «было/стало»: у удалённой строки он из
 * старого файла, у остальных — из нового. `null` — строке номера не положено:
 * шапка, сам заголовок ханка, `\ No newline at end of file`.
 */
const parseLines = (lines) => {
  let inHunk = false;
  let oldNo = 0;
  let newNo = 0;

  return lines.map((line) => {
    if (line.startsWith('@@')) {
      inHunk = true;
      const hunk = HUNK.exec(line);
      // Заголовок без разбираемых чисел не даёт точки отсчёта — дальше по
      // ханку номеров не будет вовсе, лучше их отсутствие, чем выдуманные.
      oldNo = hunk ? Number(hunk[1]) : 0;
      newNo = hunk ? Number(hunk[2]) : 0;
      return { cls: 'diff-line diff-line--hunk', no: null };
    }
    // Патч на несколько файлов: со следующего `diff --git` снова идёт шапка,
    // и отсчёт начинается заново с его первого ханка.
    if (line.startsWith('diff --git ')) {
      inHunk = false;
      oldNo = 0;
      newNo = 0;
    }

    if (!inHunk && META.test(line)) return { cls: 'diff-line diff-line--meta', no: null };
    if (line.startsWith('+')) return { cls: 'diff-line diff-line--add', no: newNo ? newNo++ : null };
    if (line.startsWith('-')) return { cls: 'diff-line diff-line--del', no: oldNo ? oldNo++ : null };
    // `\ No newline…` — примечание git о самом файле, а не строка в нём.
    if (!inHunk || line.startsWith('\\')) return { cls: 'diff-line', no: null };

    if (oldNo) oldNo += 1;
    return { cls: 'diff-line', no: newNo ? newNo++ : null };
  });
};

/**
 * Строки unified diff. Возвращает только сами строки — родительский `<pre>`
 * (моноширинный шрифт, фон, скролл по горизонтали) на вызывающем: в чате это
 * `.fcd-diff`, в модалке вызова — `.tool-diff__patch`.
 *
 * `lineNumbers` включает гуттер с номерами строк — тот же приём, что у
 * текстовых блоков модалки (`codeLines.jsx`). Тогда строка становится flex-
 * рядом и переносом `\n` больше не заканчивается: его пришлось бы прятать от
 * `white-space: pre`, иначе каждая строка шла бы через пустую.
 */
export const DiffLines = ({ patch, lineNumbers = false }) => {
  const lines = patch.split('\n');
  // Хвостовая пустая строка — артефакт `split`, а не строка файла.
  if (lines.length > 1 && lines[lines.length - 1] === '') lines.pop();

  return parseLines(lines).map(({ cls, no }, i) =>
    // Индекс как key безопасен: текст diff'а иммутабелен в рамках открытой модалки.

    lineNumbers ? (
      <span key={i} className={`${cls} diff-line--numbered`}>
        <span className="diff-line__no">{no ?? ''}</span>
        <span className="diff-line__text">{lines[i] || ' '}</span>
      </span>
    ) : (
      <span key={i} className={cls}>
        {lines[i]}
        {'\n'}
      </span>
    ),
  );
};

/**
 * Запись с патчем → его шапка (`diff --git`, `index`, `--- a/…`, `+++ b/…`)
 * строками и содержимое, начиная с первого `@@`. Шапка — метаданные о файле, а
 * не его строки, и показывается она над блоком кода, а не в нём.
 *
 * Форматов ответа два, и оба живые:
 *
 * - **новый** — шапка приходит отдельным полем `patchHeader` (см. GitDiffEntry):
 *   делить нечего, границу уже провёл бэкенд;
 * - **старый** — шапка внутри `patch`. Так лежат результаты вызовов
 *   инструментов, уже сохранённые в истории чатов: их текст — дословно то, что
 *   ушло модели, и переписать его задним числом нельзя.
 *
 * Для старого формата граница — первый `@@`: дальше по патчу такие строки могут
 * быть содержимым файла, а до него ничего кроме шапки быть не может. Патч без
 * `@@` не делится вовсе: у него нет этой границы, а содержимое есть — так
 * выглядел файл вне git (`+++ b/path` и одни `+`-строки) и так выглядит
 * сообщение о бинарном файле.
 */
export const patchParts = ({ patch, patchHeader }) => {
  if (patchHeader) return { header: patchHeader.split('\n').filter(Boolean), patch: patch ?? null };
  if (!patch) return { header: null, patch: null };

  const lines = patch.split('\n');
  const hunk = lines.findIndex((line) => line.startsWith('@@'));
  if (hunk <= 0) return { header: null, patch };

  const head = lines.slice(0, hunk).filter((line) => line !== '');
  return { header: head.length > 0 ? head : null, patch: lines.slice(hunk).join('\n') };
};

/**
 * Шапка патча над блоком кода — то, что отделил `splitPatch`. Ничего не рисует
 * без строк, поэтому вызывающему не нужна собственная проверка.
 */
export const PatchHeader = ({ lines }) => {
  if (!lines || lines.length === 0) return null;

  return (
    <div className="diff-meta">
      {lines.map((line, i) => (
        // Индекс как key безопасен: текст патча открытого файла неизменен.
        // title обязателен: строка режется многоточием, прокрутки у неё нет,
        // и длинный путь иначе не прочитать.
        <span key={i} className="diff-meta__line" title={line}>
          {line}
        </span>
      ))}
    </div>
  );
};

/** Счётчики строк `+N/−M`. */
export const DiffStats = ({ additions, deletions }) => (
  <span className="diff-stats">
    <span className="diff-stats__add">+{additions}</span>/<span className="diff-stats__del">−{deletions}</span>
  </span>
);
