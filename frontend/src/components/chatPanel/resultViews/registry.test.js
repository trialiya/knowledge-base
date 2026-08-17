import { detectResultView, parseResult } from './registry';

// Реестр отвечает за две вещи: разобрать ответ один раз и выбрать первый
// подошедший вид. Что именно каждый вид считает своей формой — в его тестах.

describe('parseResult', () => {
  it('пустой ответ входом не становится', () => {
    expect(parseResult('')).toBeNull();
    expect(parseResult(null)).toBeNull();
    expect(parseResult(undefined)).toBeNull();
  });

  it('не JSON помечается флагом, а не выбрасывается', () => {
    expect(parseResult('просто текст')).toMatchObject({ isJson: false, parsed: null, resultText: 'просто текст' });
    expect(parseResult('{"a":1}')).toMatchObject({ isJson: true, parsed: { a: 1 } });
  });
});

describe('detectResultView', () => {
  it('diff выбирается раньше текста', () => {
    const view = detectResultView(
      JSON.stringify({ operation: 'edit', path: 'a.js', additions: 1, deletions: 1, diff: '@@ -1 +1 @@\n-a\n+b' }),
    );
    expect(view.id).toBe('diff');
  });

  it('текстовый результат достаётся виду content', () => {
    const view = detectResultView(JSON.stringify({ path: 'a.md', content: 'x\n'.repeat(20) }));
    expect(view.id).toBe('content');
  });

  it('список записей — recordList, а список текстов всё равно content', () => {
    const commits = JSON.stringify([{ shortHash: 'abc', author: 'kb', message: 'fix', files: null }]);
    expect(detectResultView(commits).id).toBe('recordList');

    const texts = JSON.stringify([{ id: 1, fileName: 'a.md', content: 'x\n'.repeat(20) }]);
    expect(detectResultView(texts).id).toBe('content');
  });

  it('иерархию забирает tree, даже если запись подошла бы и списком', () => {
    // Пути и ссылки на родителя — иерархия, и вид дерева стоит в реестре выше:
    // ровно так порядок и уточняет уже работающий отбор.
    const files = JSON.stringify([{ path: 'a/b.java', name: 'b.java', type: 'FILE', size: 12 }]);
    expect(detectResultView(files).id).toBe('tree');
  });

  // `getTreeSkeleton` и `findDocumentsByName` возвращают одни и те же
  // DocumentNode, но заполняют их по-разному: скелет отдаёт пустое описание,
  // поиск по имени — снипет в 150 символов (`toStubNode`). Тесты ниже держат
  // обе настоящие формы, а не общий знаменатель между ними.
  const skeleton = (id, title, parentId) => ({
    id,
    title,
    type: 'document',
    parentId,
    version: 2,
    description: '',
    descriptionVersion: 3,
    createdAt: null,
    updatedAt: null,
    children: [],
    hasChildren: false,
    system: false,
  });
  const found = (id, title, parentId, description) => ({
    ...skeleton(id, title, parentId),
    description,
    createdAt: '2026-05-01T10:00:00',
    updatedAt: '2026-08-01T12:00:00',
  });

  it('getTreeSkeleton — tree: описание пустое, а записи ссылаются друг на друга', () => {
    expect(detectResultView(JSON.stringify([skeleton(1, 'Проект', null), skeleton(7, 'Модели', 1)])).id).toBe('tree');
  });

  it('findDocumentsByName — recordList: родители лежат снаружи выдачи', () => {
    const one = 'Слои приложения и их назначение, коротко и в одну строку.';
    expect(detectResultView(JSON.stringify([found(7, 'Модели', 1, one), found(31, 'Отчёты', 4, one)])).id).toBe(
      'recordList',
    );
  });

  it('findDocumentsByName: часть снипетов многострочная — список, а не провал в JSON', () => {
    // Снипет markdown-документа почти всегда несёт перенос строки, и по
    // предикату это «текст». Пока таких записей не все, массив целиком `content`
    // не возьмёт, и уступать ему нечему.
    const md = '# Обзор\n\nЗапрос проходит через четыре слоя, каждый следующий не знает о преды';
    const one = 'Слои приложения и их назначение, коротко и в одну строку.';
    expect(detectResultView(JSON.stringify([found(7, 'Модели', 1, md), found(31, 'Отчёты', 4, one)])).id).toBe(
      'recordList',
    );
  });

  it('findDocumentsByName: все снипеты многострочные — пока текст', () => {
    // Осознанный текущий исход, а не недосмотр: снипеты выглядят ровно как
    // короткие тексты, и отличить их от них можно только порогом
    // `isContentText`, который делит границу ещё и со `scalar`.
    const md = '# Обзор\n\nЗапрос проходит через четыре слоя, каждый следующий не знает о преды';
    expect(detectResultView(JSON.stringify([found(7, 'Модели', 1, md), found(31, 'Отчёты', 4, md)])).id).toBe(
      'content',
    );
  });

  it('документ с вложенными — всё ещё текст: children не отменяют description', () => {
    const doc = JSON.stringify({
      id: 1,
      title: 'Проект',
      description: 'x\n'.repeat(20),
      descriptionVersion: 3,
      children: [{ id: 2, title: 'Раздел' }],
    });
    expect(detectResultView(doc).id).toBe('content');
  });

  // Уступка «список текстов — за content» точна ровно настолько, насколько
  // совпадают два описания одной границы. Здесь она проверяется с той стороны,
  // где `content` текст несёт, но массив всё равно не берёт.
  it('текстов больше, чем берёт content, — список, а не провал в JSON', () => {
    const long = 'строка\n'.repeat(12);
    const many = Array.from({ length: 21 }, (_, i) => ({ id: i, fileName: `f${i}.md`, content: long }));
    expect(detectResultView(JSON.stringify(many)).id).toBe('recordList');
  });

  it('текст рядом с вложенной коллекцией — тоже список', () => {
    // `content` отбивается по вложенной коллекции, и уступать ему тут нечего.
    const long = 'строка\n'.repeat(12);
    const records = [1, 2].map((id) => ({ id, title: `Док ${id}`, description: long, sections: [{ level: 1 }] }));
    expect(detectResultView(JSON.stringify(records)).id).toBe('recordList');
  });

  it('совпадения поиска — grepMatches, а не список и не текст', () => {
    const matches = JSON.stringify([{ path: 'a.java', matchLine: 85, text: '-84- a;\n:85: b;\n' }]);
    expect(detectResultView(matches).id).toBe('grepMatches');
  });

  it('короткое значение — scalar', () => {
    expect(detectResultView('"Done"').id).toBe('scalar');
  });

  it('документная мутация — docMutation, а само оглавление того же документа — tree', () => {
    const mutation = JSON.stringify({
      id: 75,
      title: 'анализ',
      type: 'folder',
      parentId: null,
      version: 1,
      descriptionVersion: 1,
      updatedAt: '2026-07-18T21:00:55',
      summaryStale: false,
      summarySourceVersion: null,
    });
    expect(detectResultView(mutation).id).toBe('docMutation');

    const outline = JSON.stringify({
      id: 75,
      title: 'анализ',
      version: 1,
      descriptionVersion: 1,
      sections: [{ path: 'Слои', level: 2, title: 'Слои', chars: 640, subsections: 0 }],
    });
    expect(detectResultView(outline).id).toBe('tree');
  });

  it('прогон скрипта — scriptRun, хотя его правки формой подошли бы и diff’у', () => {
    // Вид, который содержит другой вид, обязан стоять выше него: иначе ответ
    // разобрали бы по частям и лог со статистикой потерялись бы.
    const script = JSON.stringify({
      value: null,
      log: ['готово'],
      stats: { filesRead: 2, bytesRead: 4096, calls: 5, filesEdited: 1, elapsedMs: 120 },
      error: null,
      filesRead: ['a.jsx'],
      edits: [
        { operation: 'edit', path: 'a.jsx', additions: 3, deletions: 1, lineCount: 42, diff: '@@ -1 +1 @@\n-a\n+b' },
      ],
    });
    expect(detectResultView(script).id).toBe('scriptRun');
  });

  it('форма без вида — обзора нет вовсе', () => {
    // Плоский объект без заголовка, текста и версий: показывать нечего, кроме
    // самого JSON, — и модалка так и делает, без переключателя режимов.
    expect(detectResultView(JSON.stringify({ ok: true, count: 3 }))).toBeNull();
    expect(detectResultView('')).toBeNull();
  });
});
