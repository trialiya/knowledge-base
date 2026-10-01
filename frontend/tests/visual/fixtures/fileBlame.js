/**
 * Фикстура колонки blame: исходник в «Файлах» с включённой колонкой авторства
 * (components/filesPanel/code/CodeView.jsx, BlameCell.jsx) и строками,
 * выделенными адресом (`?lines=`) — туда ведёт клик по ячейке blame.
 *
 * Ханки подобраны под то, что колонке есть показать: короткое описание целиком,
 * длинное — обрезанное многоточием по ширине колонки, однострочный ханк и
 * незакоммиченную правку. Выделенный диапазон совпадает с ханком в середине
 * файла: подсветка строк и растянутая ячейка встречаются в одной таблице. Данные
 * синтетические, по форме — ответы `GET /api/git/files/content` и
 * `GET /api/git/files/blame`.
 */

const A = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678';
const B = 'b2c3d4e5f60718293a4b5c6d7e8f9012345678a1';
const C = 'c3d4e5f60718293a4b5c6d7e8f9012345678a1b2';

const blameColumnPath = 'backend/src/main/java/io/github/trialiya/kb/service/document/DocumentFunction.java';

/** Выделенные строки — как они стоят в адресе. */
export const lines = '5-9';

export const blameColumn = {
  path: blameColumnPath,
  file: {
    language: 'java',
    lineCount: 16,
    sizeBytes: 640,
    tracked: true,
    content: [
      'package io.github.trialiya.kb.service.document;',
      '',
      'public final class DocumentFunction {',
      '',
      '    /**',
      '     * Finds documents whose title contains {@code name} (case-insensitive).',
      '     * Exact matches are returned first.',
      '     */',
      '    public List<DocumentNode> findByName(String name) {',
      '        return repo.findByTitleContaining(name).stream().map(this::toStubNode).toList();',
      '    }',
      '',
      '    @Nullable',
      '    DocumentNode findById(long id) {',
      '        return repo.findById(id).map(this::toNode).orElse(null);',
      '    }',
    ].join('\n'),
  },
  blame: {
    path: blameColumnPath,
    commit: null,
    lineCount: 16,
    hunks: [
      hunk(1, 4, A, 'Initial import', '2025-11-03T09:12:00Z', 1),
      hunk(
        5,
        5,
        B,
        'hybridSearch + DocumentFunction: exact title matches first, partial ordered by length',
        '2026-05-20T06:58:49Z',
        5,
      ),
      { fromLine: 10, lineCount: 1, hash: null },
      hunk(11, 1, A, 'Initial import', '2025-11-03T09:12:00Z', 7),
      hunk(12, 5, C, 'findById', '2026-07-13T14:30:00Z', 8),
    ],
  },
};

function hunk(fromLine, lineCount, hash, summary, date, sourceLine) {
  return {
    fromLine,
    lineCount,
    hash,
    shortHash: hash.slice(0, 7),
    author: 'Trialiya',
    email: 'trialiya@example.com',
    date,
    summary,
    path: blameColumnPath,
    sourceLine,
  };
}
