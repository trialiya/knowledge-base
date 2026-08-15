import { detectContentResult } from './contentResult';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда:
// GitFileContent, DocumentNode, DocumentSection, AttachmentContext).

const long = (line) => Array.from({ length: 12 }, (_, i) => `${line} ${i}`).join('\n');

describe('detectContentResult — что попадает в «Обзор»', () => {
  it('getFileContent: текст, язык и смещение номеров строк из fromLine', () => {
    const items = detectContentResult(
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

  it('бинарный файл — блок без текста, а не отсутствие обзора', () => {
    const items = detectContentResult(
      JSON.stringify({ path: 'logo.png', content: null, binary: true, sizeBytes: 2048, lineCount: 0 }),
    );
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ title: 'logo.png', binary: true, text: null });
  });

  it('документ и его секция считаются markdown по descriptionVersion', () => {
    const doc = detectContentResult(
      JSON.stringify({
        id: 7,
        title: 'Архитектура',
        type: 'document',
        description: long('## Раздел'),
        descriptionVersion: 4,
      }),
    );
    expect(doc[0]).toMatchObject({ title: 'Архитектура', markdown: true });

    const section = detectContentResult(
      JSON.stringify({ id: 7, path: 'Архитектура/Слои', descriptionVersion: 4, content: long('текст') }),
    );
    expect(section[0]).toMatchObject({ title: 'Архитектура/Слои', markdown: true });
  });

  it('getAttachmentContentByFileName: массив → блок на вложение', () => {
    const items = detectContentResult(
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
    const items = detectContentResult(JSON.stringify(long('строка')), JSON.stringify({ fileName: 'readme.md' }));
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ title: 'readme.md', startLine: 1, markdown: true });
  });

  it('ответ не в JSON вовсе тоже читается как текст', () => {
    const items = detectContentResult(long('plain'));
    expect(items[0].text).toBe(long('plain'));
  });
});

describe('detectContentResult — что остаётся в JSON', () => {
  it('короткий скаляр: getChatId, recordChatInsights', () => {
    expect(detectContentResult('"Done"')).toBeNull();
    expect(detectContentResult(JSON.stringify('a1b2c3'))).toBeNull();
    expect(detectContentResult('42')).toBeNull();
    expect(detectContentResult('')).toBeNull();
    expect(detectContentResult(null)).toBeNull();
  });

  it('вложенная коллекция — форма другого вида (дерево, коммиты, правки скрипта)', () => {
    expect(
      detectContentResult(
        JSON.stringify({ id: 1, title: 'Проект', description: long('x'), children: [{ id: 2, title: 'Раздел' }] }),
      ),
    ).toBeNull();
    expect(
      detectContentResult(JSON.stringify([{ hash: 'abc', message: 'fix', files: [{ path: 'a', patch: long('@@') }] }])),
    ).toBeNull();
  });

  it('разнородный список показывается целиком, а не частью', () => {
    expect(
      detectContentResult(
        JSON.stringify([
          { fileName: 'a.md', content: long('a') },
          { id: 9, hasChildren: true },
        ]),
      ),
    ).toBeNull();
  });

  it('пустой список и список длиннее лимита', () => {
    expect(detectContentResult('[]')).toBeNull();
    const many = Array.from({ length: 21 }, (_, i) => ({ fileName: `f${i}.txt`, content: long('x') }));
    expect(detectContentResult(JSON.stringify(many))).toBeNull();
  });
});
