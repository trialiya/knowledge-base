import { useMemo } from 'react';
import BlameCell from './BlameCell';
import { blameRows } from './blameRows';

/**
 * Исходник построчно: таблица «[blame] · номер · текст».
 *
 * `blame` — `{ hunks }` из useFileBlame, когда колонка включена, иначе null: без
 * неё в строке только номер и текст. Пока ответа нет, колонка стоит пустой —
 * той же ширины, чтобы текст не прыгал, когда подписи придут.
 *
 * С колонкой каждый ханк — свой `<tbody>`: подсветка при наведении на подпись
 * ложится на все его строки разом (blame.css), а не на одну, к которой
 * пристёгнута растянутая ячейка.
 */
const CodeView = ({ text, fromLine = 1, showLineNumbers = true, blame = null, path = '', project = '' }) => {
  const lines = text.split('\n');
  const hunks = blame?.hunks;
  const rows = useMemo(
    () => (hunks ? blameRows(hunks, fromLine, lines.length) : null),
    [hunks, fromLine, lines.length],
  );
  const row = (line, i) => (
    // Номер строки как адрес — для прокрутки к символу структуры (FileView), и
    // только там, где нумерация честная.
    <tr key={i} data-line={showLineNumbers ? fromLine + i : undefined}>
      {rows && rows[i] && <BlameCell hunk={rows[i].hunk} span={rows[i].span} path={path} project={project} />}
      {showLineNumbers && <td className="file-code__gutter">{fromLine + i}</td>}
      <td className="file-code__line">
        <code>{line.length ? line : ' '}</code>
      </td>
    </tr>
  );
  return (
    <div className="file-code">
      <table className={`file-code__table${rows ? ' file-code__table--blame' : ''}`}>
        {rows ? (
          hunkGroups(rows).map(([from, to]) => (
            <tbody key={from}>{lines.slice(from, to).map((line, k) => row(line, from + k))}</tbody>
          ))
        ) : (
          <tbody>{lines.map(row)}</tbody>
        )}
      </table>
    </div>
  );
};

/** Границы ханков `[from, to)` по раскладке blameRows: группа начинается там, где начинается ячейка. */
function hunkGroups(rows) {
  const groups = [];
  rows.forEach((r, i) => {
    if (r) groups.push([i, i + r.span]);
  });
  return groups;
}

export default CodeView;
