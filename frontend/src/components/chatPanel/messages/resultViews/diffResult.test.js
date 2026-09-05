import { detectDiffResult } from './diffResult';
import { parseResult } from './registry';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда: GitCommit,
// GitDiffEntry, GitEditResult); фрагменты патчей взяты из sample-data.sql.

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectDiffResult(input) : null;
};

const HEADER = [
  'diff --git a/backend/build.gradle b/backend/build.gradle',
  'index 82e3860..0b749e9 100644',
  '--- a/backend/build.gradle',
  '+++ b/backend/build.gradle',
];

const BODY = [
  '@@ -1,6 +1,6 @@',
  ' plugins {',
  "-    id 'org.springframework.boot' version '3.5.14'",
  "+    id 'org.springframework.boot' version '3.5.15'",
  ' }',
].join('\n');

const PATCH = [...HEADER, BODY].join('\n');

const commit = (files) => ({
  hash: '38e5ba2c6941bf43815588d2dbbdb1d5be9590ce',
  shortHash: '38e5ba2',
  author: 'dependabot[bot]',
  email: 'bot@users.noreply.github.com',
  date: '2026-06-17T23:58:42+03:00',
  message: 'build(deps): bump org.springframework.boot',
  files,
});

const entry = (over = {}) => ({
  status: 'M',
  path: 'backend/build.gradle',
  oldPath: null,
  additions: 1,
  deletions: 1,
  patch: PATCH,
  ...over,
});

describe('detectDiffResult — что попадает в «Обзор»', () => {
  it('тело сообщения попадает в шапку, а его отсутствие не выдумывается', () => {
    const body = 'Первый абзац.\n\nВторой абзац.';
    const [withBody] = detect(JSON.stringify([{ ...commit([entry()]), body }]));
    expect(withBody.commit.body).toBe(body);

    const [withoutBody] = detect(JSON.stringify([commit([entry()])]));
    expect(withoutBody.commit.body).toBeNull();
  });

  it('getCommitDiff: коммит с файлами → группа с шапкой', () => {
    const groups = detect(JSON.stringify([commit([entry()])]));
    expect(groups).toHaveLength(1);
    expect(groups[0].commit).toMatchObject({ hash: '38e5ba2', author: 'dependabot[bot]' });
    expect(groups[0].files[0]).toMatchObject({
      path: 'backend/build.gradle',
      status: 'M',
      additions: 1,
      deletions: 1,
      oldPath: null,
    });
    expect(groups[0].files[0]).toMatchObject({ header: HEADER, patch: BODY });
  });

  it('патч без ханков не делится: у него нет границы, а содержимое есть', () => {
    // Файл вне git (`U`): бэкенд отдаёт `+++ b/path` и одни `+`-строки, без `@@`.
    const untracked = ['+++ b/new.txt', '+первая строка', '+вторая'].join('\n');
    const [{ files }] = detect(JSON.stringify([entry({ status: 'U', path: 'new.txt', patch: untracked })]));
    expect(files[0]).toMatchObject({ status: 'U', header: null, patch: untracked });

    const binary = ['diff --git a/logo.png b/logo.png', 'Binary files a/logo.png and b/logo.png differ'].join('\n');
    const [{ files: bin }] = detect(JSON.stringify([entry({ path: 'logo.png', patch: binary })]));
    expect(bin[0]).toMatchObject({ header: null, patch: binary });
  });

  it('новый формат: шапка приходит полем patchHeader, патч делить не надо', () => {
    // Так отвечает бэкенд сегодня; старые вызовы, сохранённые в истории чатов,
    // приходят предыдущей формой — оба разбираются одним patchParts.
    const [{ files }] = detect(JSON.stringify([entry({ patchHeader: HEADER.join('\n'), patch: BODY })]));
    expect(files[0]).toMatchObject({ header: HEADER, patch: BODY });
  });

  it('новый формат: шапка без ханков (файл вне git) тоже показывается над блоком', () => {
    const body = ['+первая строка', '+вторая'].join('\n');
    const [{ files }] = detect(
      JSON.stringify([entry({ status: 'U', path: 'new.txt', patchHeader: '+++ b/new.txt', patch: body })]),
    );
    expect(files[0]).toMatchObject({ status: 'U', header: ['+++ b/new.txt'], patch: body });
  });

  it('патч без шапки отдаётся как есть', () => {
    const [{ files }] = detect(JSON.stringify([entry({ patch: BODY })]));
    expect(files[0]).toMatchObject({ header: null, patch: BODY });
  });

  it('getUncommittedChanges: плоский список файлов → одна группа без коммита', () => {
    const groups = detect(JSON.stringify([entry(), entry({ status: 'A', path: 'new.txt', deletions: 0 })]));
    expect(groups).toHaveLength(1);
    expect(groups[0].commit).toBeNull();
    expect(groups[0].files.map((f) => f.status)).toEqual(['M', 'A']);
  });

  it('editFile: одиночный GitEditResult, статус выводится из operation', () => {
    const groups = detect(
      JSON.stringify({
        operation: 'edit',
        path: 'src/App.jsx',
        additions: 12,
        deletions: 3,
        lineCount: 240,
        diff: PATCH,
      }),
    );
    expect(groups[0].files[0]).toMatchObject({ status: 'M', path: 'src/App.jsx', header: HEADER, patch: BODY });
  });

  it('createFile: патча нет, но форма та же — список путей со счётчиками', () => {
    const groups = detect(
      JSON.stringify({
        operation: 'create',
        path: 'src/New.jsx',
        additions: 30,
        deletions: 0,
        lineCount: 30,
        diff: null,
      }),
    );
    expect(groups[0].files[0]).toMatchObject({ status: 'A', patch: null, additions: 30 });
  });

  it('переименование: старый путь сохраняется, совпадающий — отбрасывается', () => {
    const renamed = detect(JSON.stringify([entry({ status: 'R', oldPath: 'old/build.gradle' })]));
    expect(renamed[0].files[0]).toMatchObject({ status: 'R', oldPath: 'old/build.gradle' });

    const same = detect(JSON.stringify([entry({ oldPath: 'backend/build.gradle' })]));
    expect(same[0].files[0].oldPath).toBeNull();
  });
});

describe('detectDiffResult — что остаётся в JSON', () => {
  it('getCommitLog: коммиты без files — это список, а не diff', () => {
    expect(detect(JSON.stringify([commit(null)]))).toBeNull();
    expect(detect(JSON.stringify([commit([])]))).toBeNull();
  });

  it('запись с путём, но без счётчиков строк', () => {
    // getFileTree и getFileContent тоже несут path — счётчики отличают diff.
    expect(detect(JSON.stringify([{ path: 'a/b.java', name: 'b.java', type: 'FILE', size: 120 }]))).toBeNull();
    expect(detect(JSON.stringify({ path: 'a/b.java', content: 'package a;', lineCount: 1 }))).toBeNull();
  });

  it('разнородный список показывается целиком, а не частью', () => {
    expect(detect(JSON.stringify([entry(), { hash: 'abc', message: 'fix' }]))).toBeNull();
  });

  it('пустой список, не JSON и список длиннее лимита', () => {
    expect(detect('[]')).toBeNull();
    expect(detect('')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
    const many = Array.from({ length: 201 }, (_, i) => entry({ path: `f${i}.txt` }));
    expect(detect(JSON.stringify(many))).toBeNull();
  });
});
