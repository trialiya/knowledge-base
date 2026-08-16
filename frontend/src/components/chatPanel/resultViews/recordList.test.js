import { detectRecordList } from './recordList';
import { parseResult } from './registry';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда: GitCommit,
// GitFileNode, SearchResult, Attachment, DocumentNode).

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectRecordList(input) : null;
};

const commit = (over = {}) => ({
  hash: '8547d567e4c524805f74b0a523be2a8ec3892c1e',
  shortHash: '8547d56',
  author: 'trialiya',
  email: 'trialiya@example.org',
  date: '2026-07-13T02:41:21+03:00',
  message: 'Replace git subprocess calls with JGit',
  files: null,
  ...over,
});

describe('detectRecordList — что попадает в «Обзор»', () => {
  it('getCommitLog: заголовок из message, коммит и автор — в чипы', () => {
    const records = detect(JSON.stringify([commit(), commit({ shortHash: 'ccb7fe3', message: 'Improve guidance' })]));
    expect(records).toHaveLength(2);
    expect(records[0].title).toBe('Replace git subprocess calls with JGit');
    expect(records[0].meta.map((m) => m.key)).toEqual(['shortHash', 'author', 'date']);
    // Поле, ушедшее в заголовок, в развороте не повторяется.
    expect(records[0].fields.map((f) => f.key)).not.toContain('message');
    expect(records[0].fields.map((f) => f.key)).toContain('hash');
  });

  it('getFileTree: заголовок из path, размер и тип — в чипы', () => {
    const records = detect(
      JSON.stringify([
        { path: 'backend/build.gradle', name: 'build.gradle', type: 'FILE', size: 5218 },
        { path: 'backend/src', name: 'src', type: 'DIRECTORY', size: null },
      ]),
    );
    expect(records.map((r) => r.title)).toEqual(['backend/build.gradle', 'backend/src']);
    expect(records[0].meta.map((m) => m.key)).toEqual(['type', 'size']);
    // null-поля не показываются ни в чипах, ни в развороте.
    expect(records[1].meta.map((m) => m.key)).toEqual(['type']);
  });

  it('searchDocuments: пояснение из snippet, крошки остаются полем', () => {
    const records = detect(
      JSON.stringify([
        {
          id: 12,
          title: 'Архитектура',
          snippet: 'слои Controller → Service → Repository',
          updatedAt: '2026-08-12T10:00:00',
          summary: null,
          parentList: [
            { id: 1, title: 'Проект' },
            { id: 4, title: 'Разработка' },
          ],
        },
      ]),
    );
    expect(records[0]).toMatchObject({ title: 'Архитектура', subtitle: 'слои Controller → Service → Repository' });
    expect(records[0].fields.map((f) => f.key)).toEqual(['id', 'updatedAt', 'parentList']);
  });

  it('вложения: заголовок из fileName, пояснение из summary, одна дата в строке', () => {
    const records = detect(
      JSON.stringify([
        {
          id: 1,
          ownerType: 'chat',
          fileName: 'gradle-build-error.log',
          contentType: 'text/plain',
          fileSize: 2048,
          summary: 'Ошибка сборки: не найден тулчейн Java 25',
          createdAt: '2026-07-18T20:58:02+03:00',
          updatedAt: '2026-07-18T20:58:02+03:00',
        },
      ]),
    );
    expect(records[0]).toMatchObject({
      title: 'gradle-build-error.log',
      subtitle: 'Ошибка сборки: не найден тулчейн Java 25',
    });
    // createdAt и updatedAt — один слот: в строке одна дата, а не две одинаковые.
    expect(records[0].meta.map((m) => m.key)).toEqual(['contentType', 'fileSize', 'updatedAt']);
    // В развороте обе на месте — чип косметика, а не фильтр.
    expect(records[0].fields.map((f) => f.key)).toEqual(expect.arrayContaining(['createdAt', 'updatedAt']));
  });

  it('getTreeSkeleton: узлы без описания — пока плоский список', () => {
    // Своего вида у дерева ещё нет; когда появится, он встанет в реестре выше.
    const records = detect(
      JSON.stringify([
        { id: 1, title: 'Проект', type: 'folder', parentId: null, description: null, children: [], hasChildren: true },
        {
          id: 7,
          title: 'Модели данных',
          type: 'folder',
          parentId: 1,
          description: null,
          children: [],
          hasChildren: true,
        },
      ]),
    );
    expect(records.map((r) => r.title)).toEqual(['Проект', 'Модели данных']);
  });
});

describe('detectRecordList — что остаётся другим видам', () => {
  it('список текстов — за content', () => {
    const long = 'строка\n'.repeat(12);
    expect(detect(JSON.stringify([{ id: 1, fileName: 'a.md', content: long }]))).toBeNull();
    expect(detect(JSON.stringify([{ id: 1, title: 'Док', description: long }]))).toBeNull();
  });

  it('разнобой в наборе ключей — форма не та', () => {
    expect(detect(JSON.stringify([commit(), { id: 9, title: 'Док' }]))).toBeNull();
  });

  it('записи без заголовка — столбец пустых кнопок не нужен', () => {
    expect(
      detect(
        JSON.stringify([
          { id: 1, version: 3 },
          { id: 2, version: 4 },
        ]),
      ),
    ).toBeNull();
  });

  it('одиночный объект, пустой список, не массив и не JSON', () => {
    expect(detect(JSON.stringify(commit()))).toBeNull();
    expect(detect('[]')).toBeNull();
    expect(detect('"Done"')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });

  it('список длиннее лимита', () => {
    const many = Array.from({ length: 501 }, (_, i) => ({ path: `f${i}.txt`, name: `f${i}.txt`, type: 'FILE' }));
    expect(detect(JSON.stringify(many))).toBeNull();
  });
});
