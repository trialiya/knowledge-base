import { detectTreeResult } from './treeResult';
import { parseResult } from './registry';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда: DocumentNode,
// DocumentOutline.OutlineSection, GitFileOutline/GitSymbol, GitFileNode).

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectTreeResult(input) : null;
};

/** Плоское дерево из узла: [label, [дети…]] — так вложенность видно в тесте. */
const shape = (nodes) => nodes.map((node) => (node.children.length ? [node.label, shape(node.children)] : node.label));

const doc = (id, title, parentId) => ({
  id,
  title,
  type: 'folder',
  parentId,
  version: 1,
  description: null,
  descriptionVersion: 1,
  children: [],
  hasChildren: false,
});

describe('detectTreeResult — вложенность по ссылке на родителя', () => {
  it('getTreeSkeleton: узлы собираются в дерево по parentId', () => {
    const data = detect(
      JSON.stringify([doc(1, 'Проект', null), doc(7, 'Модели', 1), doc(9, 'Документы', 7), doc(8, 'Обзор', 1)]),
    );
    expect(shape(data.nodes)).toEqual([['Проект', [['Модели', ['Документы']], 'Обзор']]]);
    expect(data.count).toBe(4);
    expect(data.header).toBeNull();
  });

  it('узел, чьего родителя в выдаче нет, — корень: это поддерево, а не ошибка', () => {
    const data = detect(JSON.stringify([doc(7, 'Модели', 1), doc(9, 'Документы', 7)]));
    expect(shape(data.nodes)).toEqual([['Модели', ['Документы']]]);
  });

  it('цикл в ссылках рисовать нельзя — даже частичный', () => {
    expect(detect(JSON.stringify([doc(1, 'A', 2), doc(2, 'B', 1)]))).toBeNull();
    // Корень тут есть, но A и B замкнуты друг на друга и не попали бы ни под
    // один корень: молча потерять их хуже, чем показать JSON.
    expect(detect(JSON.stringify([doc(5, 'Корень', null), doc(1, 'A', 2), doc(2, 'B', 1)]))).toBeNull();
  });

  it('повторяющийся id — записи склеились бы в один узел', () => {
    expect(detect(JSON.stringify([doc(1, 'Проект', null), doc(1, 'Двойник', null)]))).toBeNull();
  });

  it('findDocumentsByName: ни одной связи внутри выдачи — это список, а не дерево', () => {
    // Та же форма DocumentNode, но найденная по имени: родители лежат снаружи
    // выдачи, и дерево выродилось бы в столбец одиночных корней.
    expect(detect(JSON.stringify([doc(7, 'Модели', 1), doc(31, 'Отчёты', 4)]))).toBeNull();
    // Одна связь — уже иерархия, и она видна.
    expect(detect(JSON.stringify([doc(7, 'Модели', 1), doc(9, 'Документы', 7)]))).not.toBeNull();
  });
});

describe('detectTreeResult — вложенность по уровню заголовка', () => {
  const section = (path, level, title, chars) => ({ path, level, title, chars, subsections: 0 });

  it('getDocumentOutline: H2 вкладывается в H1, преамбула остаётся корнем', () => {
    const data = detect(
      JSON.stringify({
        id: 76,
        title: 'Хронология',
        descriptionVersion: 4,
        sections: [
          section('', 0, '', 120),
          section('Обзор', 1, 'Обзор', 800),
          section('Обзор > Слои', 2, 'Слои', 400),
          section('Обзор > Слои > API', 3, 'API', 150),
          section('Итоги', 1, 'Итоги', 200),
        ],
      }),
    );
    expect(shape(data.nodes)).toEqual(['—', ['Обзор', [['Слои', ['API']]]], 'Итоги']);
    expect(data.header).toMatchObject({ label: 'Хронология' });
  });
});

describe('detectTreeResult — вложенность по диапазону строк', () => {
  const symbol = (kind, name, startLine, endLine) => ({ kind, name, signature: null, startLine, endLine });

  it('getFileOutline: метод внутри класса, импорты рядом', () => {
    const data = detect(
      JSON.stringify({
        path: 'backend/src/main/java/Git.java',
        language: 'java',
        lineCount: 323,
        parser: 'tree-sitter',
        symbols: [
          symbol('import', 'java.util.List', 3, 3),
          symbol('class', 'GitService', 10, 300),
          symbol('field', 'log', 12, 12),
          symbol('method', 'readFile', 20, 60),
          symbol('class', 'Helper', 305, 320),
        ],
      }),
    );
    expect(shape(data.nodes)).toEqual(['java.util.List', ['GitService', ['log', 'readFile']], 'Helper']);
    expect(data.header.meta.map((m) => m.key)).toEqual(['language', 'lineCount', 'parser']);
  });

  it('символы на одной строке — соседи: вложить их друг в друга не во что', () => {
    const data = detect(
      JSON.stringify({
        path: 'a/App.java',
        lineCount: 20,
        symbols: [symbol('import', 'java.util.List', 3, 3), symbol('import', 'java.util.Map', 3, 3)],
      }),
    );
    expect(shape(data.nodes)).toEqual(['java.util.List', 'java.util.Map']);
  });
});

describe('detectTreeResult — вложенность по пути', () => {
  it('getFileTree: промежуточные каталоги достраиваются', () => {
    const data = detect(
      JSON.stringify([
        { path: 'backend/build.gradle', name: 'build.gradle', type: 'FILE', size: 5218 },
        { path: 'backend/src/main/App.java', name: 'App.java', type: 'FILE', size: 120 },
        { path: 'settings.gradle', name: 'settings.gradle', type: 'FILE', size: 90 },
      ]),
    );
    // `src` и `main` в выдаче не встречались — узлы для них создаются сами.
    expect(shape(data.nodes)).toEqual([
      ['backend', ['build.gradle', ['src', [['main', ['App.java']]]]]],
      'settings.gradle',
    ]);
  });

  it('каталог бывает и записью, и родителем чужого пути — это одно место', () => {
    const data = detect(
      JSON.stringify([
        { path: 'backend', name: 'backend', type: 'DIRECTORY', size: 0 },
        { path: 'backend/App.java', name: 'App.java', type: 'FILE', size: 120 },
      ]),
    );
    expect(shape(data.nodes)).toEqual([['backend', ['App.java']]]);
    expect(data.count).toBe(2);
  });

  it('две записи с одним путём склеились бы в один лист', () => {
    expect(
      detect(
        JSON.stringify([
          { path: 'a/App.java', name: 'App.java', type: 'FILE', size: 120 },
          { path: 'a/App.java', name: 'App.java', type: 'FILE', size: 340 },
        ]),
      ),
    ).toBeNull();
  });
});

describe('detectTreeResult — что остаётся другим видам', () => {
  it('список текстов — за content', () => {
    const long = 'строка\n'.repeat(12);
    expect(detect(JSON.stringify([{ ...doc(1, 'Проект', null), description: long }]))).toBeNull();
  });

  it('записи без иерархии — за recordList', () => {
    // У коммита нет ни parentId, ни path: собрать из него дерево не из чего.
    expect(detect(JSON.stringify([{ hash: 'abc', shortHash: 'abc', message: 'fix', files: null }]))).toBeNull();
  });

  it('обёртка без секций и символов, пустой список, не JSON', () => {
    expect(detect(JSON.stringify({ id: 76, title: 'Док', sections: [] }))).toBeNull();
    expect(detect('[]')).toBeNull();
    expect(detect('"Done"')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });
});
