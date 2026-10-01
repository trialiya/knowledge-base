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
 * шапка, сам заголовок ханка, `\ No newline at end of file`. `skip` — служебная
 * строка git внутри патча (шапка, заголовок ханка, это примечание): Ctrl+F её не ищет.
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
      return { cls: 'diff-line diff-line--hunk', no: null, skip: true };
    }
    // Патч на несколько файлов: со следующего `diff --git` снова идёт шапка,
    // и отсчёт начинается заново с его первого ханка.
    if (line.startsWith('diff --git ')) {
      inHunk = false;
      oldNo = 0;
      newNo = 0;
    }

    if (!inHunk && META.test(line)) return { cls: 'diff-line diff-line--meta', no: null, skip: true };
    if (line.startsWith('+')) return { cls: 'diff-line diff-line--add', no: newNo ? newNo++ : null, sign: true };
    if (line.startsWith('-')) return { cls: 'diff-line diff-line--del', no: oldNo ? oldNo++ : null, sign: true };
    // `\ No newline…` — примечание git о самом файле, а не строка в нём.
    if (line.startsWith('\\') && inHunk) return { cls: 'diff-line', no: null, skip: true };
    if (!inHunk) return { cls: 'diff-line', no: null };

    if (oldNo) oldNo += 1;
    return { cls: 'diff-line', no: newNo ? newNo++ : null, sign: line !== '' };
  });
};

/**
 * Текст строки патча. Первый знак строки содержимого (`+`, `-`, пробел) — разметка
 * diff'а, а не файла: отдельным span'ом с `data-find-skip` он не мешает Ctrl+F
 * найти строку по её началу.
 */
const LineText = ({ line, sign }) =>
  sign ? (
    <>
      <span className="diff-line__sign" data-find-skip="">
        {line[0]}
      </span>
      {line.slice(1)}
    </>
  ) : (
    line
  );

/**
 * Строки unified diff. Возвращает только сами строки — родительский `<pre>`
 * (моноширинный шрифт, фон, скролл по горизонтали) на вызывающем: в чате это
 * `.file-diff-modal__diff`, в модалке вызова — `.tool-diff__patch`.
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

  return parseLines(lines).map(({ cls, no, sign, skip }, i) =>
    // Индекс как key безопасен: текст diff'а иммутабелен в рамках открытой модалки.

    lineNumbers ? (
      <span key={i} className={`${cls} diff-line--numbered`} data-find-skip={skip ? '' : undefined}>
        <span className="diff-line__no" data-find-skip="">
          {no ?? ''}
        </span>
        <span className="diff-line__text">{lines[i] ? <LineText line={lines[i]} sign={sign} /> : ' '}</span>
      </span>
    ) : (
      <span key={i} className={cls} data-find-skip={skip ? '' : undefined}>
        <LineText line={lines[i]} sign={sign} />
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
    // Шапка — служебный текст о файле: Ctrl+F ищет по строкам патча, а не по ней.
    <div className="diff-meta" data-find-skip="">
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
