import { detectDocMutation } from './docMutation';
import { parseResult } from './registry';

// Данные — форма настоящих ответов инструментов (см. DTO бэкенда: DocumentShort,
// DocumentNode, DocumentOutline).

const detect = (resultText) => {
  const input = parseResult(resultText);
  return input ? detectDocMutation(input) : null;
};

const short = (over = {}) => ({
  id: 75,
  title: 'Анализ',
  type: 'folder',
  parentId: null,
  version: 1,
  descriptionVersion: 1,
  updatedAt: '2026-07-18T21:00:55.850512',
  summaryStale: false,
  summarySourceVersion: null,
  ...over,
});

describe('detectDocMutation — что попадает в «Обзор»', () => {
  it('createDocument: заголовок, версия описания и факты правки', () => {
    const card = detect(JSON.stringify(short()));
    expect(card).toMatchObject({ id: 75, title: 'Анализ', descriptionVersion: 1 });
    expect(card.facts.map((f) => f.key)).toEqual(['type', 'version', 'descriptionVersion', 'updatedAt']);
  });

  it('опущенный флаг фактом не становится, поднятый — становится', () => {
    expect(detect(JSON.stringify(short())).facts.map((f) => f.key)).not.toContain('summaryStale');
    expect(detect(JSON.stringify(short({ summaryStale: true }))).facts.map((f) => f.key)).toContain('summaryStale');
  });

  it('updateDocument вложенного документа: родитель — тоже факт', () => {
    const card = detect(JSON.stringify(short({ id: 12, type: 'document', parentId: 1, version: 4 })));
    expect(card.facts.map((f) => f.key)).toContain('parentId');
  });
});

describe('detectDocMutation — что остаётся другим видам', () => {
  it('getDocument: описание есть — это документ, а не ссылка на него', () => {
    expect(detect(JSON.stringify({ ...short(), description: '', children: [], hasChildren: false }))).toBeNull();
  });

  it('getDocumentOutline: те же версии, но со списком секций', () => {
    // Оглавление отличается от карточки ровно вложенной коллекцией — без отбоя
    // по ней этот вид забирал бы его у дерева.
    expect(
      detect(JSON.stringify({ id: 76, title: 'Хронология', version: 2, descriptionVersion: 4, sections: [] })),
    ).toBeNull();
  });

  it('версия описания без версии документа — форма не та', () => {
    const { version, ...withoutVersion } = short();
    expect(version).toBe(1);
    expect(detect(JSON.stringify(withoutVersion))).toBeNull();
  });

  it('запись без заголовка, массив, скаляр и не JSON', () => {
    expect(detect(JSON.stringify(short({ title: '  ' })))).toBeNull();
    expect(detect(JSON.stringify([short()]))).toBeNull();
    expect(detect('"Done"')).toBeNull();
    expect(detect('не json вовсе')).toBeNull();
  });
});
