import { detectGrepMatches } from './grepMatches';
import { parseResult } from './registry';

// Данные — форма настоящих ответов grepContent (см. GitGrepMatch и
// GitService#parseGrepOutput): `:N:` — совпадение, `-N-` — контекст.

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectGrepMatches(input) : null;
};

const BLOCK = '-84-  BROWSE_PATHS_V2,\n:85:  SUB_TYPES,\n-86-  STRUCTURED_PROPERTIES,\n';

describe('detectGrepMatches — что попадает в «Обзор»', () => {
  it('блок с контекстом: номера из разметки, совпадение отмечено', () => {
    const data = detect(JSON.stringify([{ path: 'a/A.java', matchLine: 85, text: BLOCK }]));
    expect(data.matches).toBe(1);
    expect(data.files[0].path).toBe('a/A.java');
    expect(data.files[0].blocks[0].lines).toEqual([
      { no: 84, match: false, text: '  BROWSE_PATHS_V2,' },
      { no: 85, match: true, text: '  SUB_TYPES,' },
      { no: 86, match: false, text: '  STRUCTURED_PROPERTIES,' },
    ]);
  });

  it('без контекста разметки нет вовсе — номер берётся из matchLine', () => {
    const data = detect(JSON.stringify([{ path: 'a/A.java', matchLine: 42, text: '  int x = 1;' }]));
    expect(data.files[0].blocks[0].lines).toEqual([{ no: 42, match: true, text: '  int x = 1;' }]);
  });

  it('блоки одного файла группируются, порядок выдачи сохраняется', () => {
    const data = detect(
      JSON.stringify([
        { path: 'a/A.java', matchLine: 85, text: BLOCK },
        { path: 'b/B.java', matchLine: 12, text: ':12:  y();\n' },
        { path: 'a/A.java', matchLine: 200, text: ':200:  z();\n' },
      ]),
    );
    expect(data.files.map((f) => f.path)).toEqual(['a/A.java', 'b/B.java']);
    expect(data.files[0].blocks).toHaveLength(2);
    // Итог считается по совпадениям, а не по файлам.
    expect(data.matches).toBe(3);
  });
});

describe('detectGrepMatches — проект вызова', () => {
  it('берётся из ответа: grepContent мог искать в соседнем репозитории', () => {
    const data = detect(JSON.stringify([{ project: 'billing', path: 'a/A.java', matchLine: 1, text: 'x' }]));
    expect(data.project).toBe('billing');
  });

  it('старый ответ без проекта не ломает разбор', () => {
    const data = detect(JSON.stringify([{ path: 'a/A.java', matchLine: 1, text: 'x' }]));
    expect(data.project).toBeNull();
  });
});

describe('detectGrepMatches — что остаётся другим видам', () => {
  it('запись без номера строки или без текста', () => {
    expect(detect(JSON.stringify([{ path: 'a/A.java', text: 'x' }]))).toBeNull();
    expect(detect(JSON.stringify([{ path: 'a/A.java', matchLine: 5 }]))).toBeNull();
    expect(detect(JSON.stringify([{ matchLine: 5, text: 'x' }]))).toBeNull();
  });

  it('разнородный список показывается целиком, а не частью', () => {
    expect(
      detect(
        JSON.stringify([
          { path: 'a', matchLine: 1, text: 'x' },
          { path: 'b', name: 'b', type: 'FILE' },
        ]),
      ),
    ).toBeNull();
  });

  it('пустой список, одиночный объект и не JSON', () => {
    expect(detect('[]')).toBeNull();
    expect(detect(JSON.stringify({ path: 'a', matchLine: 1, text: 'x' }))).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });
});
