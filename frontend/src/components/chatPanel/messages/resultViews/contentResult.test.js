import { detectContentResult } from './contentResult';
import { parseResult } from './registry';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда:
// GitFileContent, DocumentNode, DocumentSection, AttachmentContext).
//
// Разбор ответа делает реестр (parseResult), детект получает уже готовый вход.
const detect = (resultText, argumentsRaw) => {
  const input = parseResult(resultText, argumentsRaw);
  return input ? detectContentResult(input) : null;
};

const long = (line) => Array.from({ length: 12 }, (_, i) => `${line} ${i}`).join('\n');

describe('detectContentResult — что попадает в «Обзор»', () => {
  it('getFileContent: текст, язык и смещение номеров строк из fromLine', () => {
    const items = detect(
      JSON.stringify({
        path: 'backend/src/main/java/Git.java',
        content: long('line'),
        binary: false,
        sizeBytes: 11542,
        language: 'java',
        lineCount: 323,
        truncated: true,
        fromLine: 59,
        toLine: 70,
      }),
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({
      title: 'backend/src/main/java/Git.java',
      language: 'java',
      startLine: 59,
      markdown: false,
      binary: false,
    });
    expect(items[0].text.split('\n')).toHaveLength(12);
    // content ушёл в текст и не дублируется в фактах; lineCount/размер — остались.
    expect(items[0].facts.map((f) => f.key)).toEqual(['language', 'lineCount', 'sizeBytes']);
  });

  it('getFileContent: проект показан фактом — вызов мог прочитать соседний репозиторий', () => {
    const items = detect(
      JSON.stringify({
        project: 'billing',
        path: 'pom.xml',
        content: long('line'),
        binary: false,
        sizeBytes: 11542,
        language: 'xml',
        lineCount: 323,
        truncated: false,
      }),
    );
    expect(items[0].facts).toContainEqual({ key: 'project', value: 'billing' });
  });

  it('бинарный файл — блок без текста, а не отсутствие обзора', () => {
    const items = detect(
      JSON.stringify({ path: 'logo.png', content: null, binary: true, sizeBytes: 2048, lineCount: 0 }),
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ title: 'logo.png', binary: true, text: null });
  });

  it('документ и его секция считаются markdown по descriptionVersion', () => {
    const doc = detect(
      JSON.stringify({
        id: 7,
        title: 'Архитектура',
        type: 'document',
        description: long('## Раздел'),
        descriptionVersion: 4,
      }),
    );
    expect(doc[0]).toMatchObject({ title: 'Архитектура', markdown: true });

    const section = detect(
      JSON.stringify({ id: 7, path: 'Архитектура/Слои', descriptionVersion: 4, content: long('текст') }),
    );
    expect(section[0]).toMatchObject({ title: 'Архитектура/Слои', markdown: true });
  });

  it('getDocument: вложенные документы не отменяют текст документа', () => {
    // `toShallowNode` заполняет `children` всегда, поэтому у любого документа с
    // вложенными они есть. Это соседи по дереву, а содержимое — в description.
    const items = detect(
      JSON.stringify({
        id: 1,
        title: 'Проект',
        type: 'folder',
        description: long('x'),
        descriptionVersion: 4,
        children: [{ id: 2, title: 'Раздел', hasChildren: false }],
        hasChildren: true,
      }),
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ title: 'Проект', markdown: true });
  });

  it('getAttachmentContentByFileName: массив → блок на вложение', () => {
    const items = detect(
      JSON.stringify([
        { id: 1, fileName: 'notes.md', content: long('a') },
        { id: 2, fileName: 'spec.txt', content: long('b') },
      ]),
    );
    expect(items.map((i) => i.title)).toEqual(['notes.md', 'spec.txt']);
    // Язык выводится из расширения, когда поля language в ответе нет.
    expect(items[0]).toMatchObject({ language: 'markdown', markdown: true });
    expect(items[1].language).toBeNull();
  });

  it('getAttachmentContent: голая строка подписывается именем из аргументов', () => {
    const items = detect(JSON.stringify(long('строка')), JSON.stringify({ fileName: 'readme.md' }));
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ title: 'readme.md', startLine: 1, markdown: true });
  });

  it('ответ не в JSON вовсе тоже читается как текст', () => {
    const items = detect(long('plain'));
    expect(items[0].text).toBe(long('plain'));
  });
});

describe('detectContentResult — что остаётся в JSON', () => {
  it('короткий скаляр: getChatId, recordChatInsights', () => {
    expect(detect('"Done"')).toBeNull();
    expect(detect(JSON.stringify('a1b2c3'))).toBeNull();
    expect(detect('42')).toBeNull();
    expect(detect('')).toBeNull();
    expect(detect(null)).toBeNull();
  });

  it('вложенная коллекция — форма другого вида (коммиты с файлами, правки скрипта)', () => {
    expect(
      detect(JSON.stringify([{ hash: 'abc', message: 'fix', files: [{ path: 'a', patch: long('@@') }] }])),
    ).toBeNull();
    expect(
      detect(JSON.stringify({ stats: { calls: 3 }, log: ['a'], edits: [{ path: 'a.js', diff: long('@@') }] })),
    ).toBeNull();
  });

  it('разнородный список показывается целиком, а не частью', () => {
    expect(
      detect(
        JSON.stringify([
          { fileName: 'a.md', content: long('a') },
          { id: 9, hasChildren: true },
        ]),
      ),
    ).toBeNull();
  });

  it('совпадения grepContent — у них своя нумерация внутри текста', () => {
    // `:85:` — строка совпадения, `-84-` — контекст (см. javadoc GitGrepMatch).
    // Гуттер «Обзора» нумерует от единицы и поверх такого текста врал бы.
    expect(
      detect(
        JSON.stringify([
          { path: 'a/A.java', matchLine: 85, text: '-84-   foo();\n:85:   bar();\n-86-   baz();' },
          { path: 'b/B.java', matchLine: 12, text: '-11-   x();\n:12:   y();\n-13-   z();' },
        ]),
      ),
    ).toBeNull();
  });

  it('пустой список и список длиннее лимита', () => {
    expect(detect('[]')).toBeNull();
    const many = Array.from({ length: 21 }, (_, i) => ({ fileName: `f${i}.txt`, content: long('x') }));
    expect(detect(JSON.stringify(many))).toBeNull();
  });
});
