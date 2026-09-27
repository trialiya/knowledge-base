import { collectDocChanges } from './docChanges';

const call = (name, meta, args = {}, status = 'OK') => ({ name, arguments: args, resultMeta: meta, status });

describe('collectDocChanges', () => {
  it('создание без правок — «создан», документ показывается целиком', () => {
    const [c] = collectDocChanges([call('createDocument', { id: 7, title: 'Док', descriptionVersion: 1 })]);
    expect(c).toMatchObject({ id: '7', title: 'Док', created: true, descriptionVersion: 1, baseVersion: 0 });
  });

  it('создание и сразу правка — одна строка, пометка «создан» сохраняется', () => {
    const changes = collectDocChanges([
      call('createDocument', { id: 7, title: 'Док', descriptionVersion: 1 }),
      call('updateDocumentSection', { id: 7, path: '1', descriptionVersion: 2 }),
      call('editDocument', { id: 7, title: 'Док', descriptionVersion: 3 }),
    ]);
    expect(changes).toEqual([{ id: '7', title: 'Док', created: true, descriptionVersion: 3, baseVersion: 0 }]);
  });

  it('несколько правок существующего документа сравниваются с версией до ответа', () => {
    const [c] = collectDocChanges([
      call('editDocument', { id: 5, title: 'A', descriptionVersion: 4 }),
      call('editDocument', { id: 5, title: 'A', descriptionVersion: 5 }),
    ]);
    expect(c).toMatchObject({ created: false, descriptionVersion: 5, baseVersion: 3 });
  });

  it('переименование до правки не сдвигает базу', () => {
    const [c] = collectDocChanges([
      call('updateDocument', { id: 5, title: 'B', descriptionVersion: 4 }, { documentId: 5, title: 'B' }),
      call('updateDocument', { id: 5, title: 'B', descriptionVersion: 5 }, { documentId: 5, description: 'x' }),
    ]);
    expect(c).toMatchObject({ title: 'B', descriptionVersion: 5, baseVersion: 4 });
  });

  it('только переименование — сравнивать нечего', () => {
    const [c] = collectDocChanges([
      call('updateDocument', { id: 5, title: 'B', descriptionVersion: 4 }, { documentId: 5, title: 'B' }),
    ]);
    expect(c).toMatchObject({ title: 'B', descriptionVersion: 4, baseVersion: null });
  });

  it('заголовок берётся из последнего вызова, где он есть', () => {
    const [c] = collectDocChanges([
      call('createDocument', { id: 7, title: 'Старое', descriptionVersion: 1 }),
      call('updateDocument', { id: 7, title: 'Новое', descriptionVersion: 1 }, { title: 'Новое' }),
      call('insertDocumentSection', { id: 7, path: '2', descriptionVersion: 2 }),
    ]);
    expect(c).toMatchObject({ title: 'Новое', created: true, descriptionVersion: 2 });
  });

  it('упавшие вызовы пропускаются, не трогая учтённые', () => {
    const changes = collectDocChanges([
      call('createDocument', { id: 7, title: 'Док', descriptionVersion: 1 }),
      call('editDocument', { id: 7, title: 'Док', descriptionVersion: 9 }, {}, 'ERROR'),
      call('editDocument', { id: 8, title: 'Чужой', descriptionVersion: 2 }, {}, 'ERROR'),
    ]);
    expect(changes).toEqual([{ id: '7', title: 'Док', created: true, descriptionVersion: 1, baseVersion: 0 }]);
  });
});
