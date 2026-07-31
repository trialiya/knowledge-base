import { getFileChangeRefs, getDocChangeRef, FILE_MUTATION_TOOLS, DOC_MUTATION_TOOLS } from './toolMeta';

describe('getFileChangeRefs', () => {
  it('returns nothing for non-mutation tools and missing meta', () => {
    expect(getFileChangeRefs(null)).toEqual([]);
    expect(getFileChangeRefs({ name: 'getFileContent', resultMeta: { path: 'a.txt' } })).toEqual([]);
    expect(getFileChangeRefs({ name: 'editFile' })).toEqual([]);
    expect(getFileChangeRefs({ name: 'editFile', resultMeta: {} })).toEqual([]);
  });

  it('maps editFile meta to a change ref', () => {
    const refs = getFileChangeRefs({
      name: 'editFile',
      status: 'OK',
      resultMeta: { path: 'src/App.java', operation: 'edit', additions: 2, deletions: 1, diff: '@@ -1 +1 @@' },
    });
    expect(refs).toEqual([
      {
        path: 'src/App.java',
        operation: 'edit',
        additions: 2,
        deletions: 1,
        diff: '@@ -1 +1 @@',
        status: 'OK',
      },
    ]);
  });

  it('defaults numbers and diff for createFile without diff', () => {
    const [ref] = getFileChangeRefs({
      name: 'createFile',
      status: 'OK',
      resultMeta: { path: 'new.txt', operation: 'create' },
    });
    expect(ref.operation).toBe('create');
    expect(ref.additions).toBe(0);
    expect(ref.deletions).toBe(0);
    expect(ref.diff).toBeNull();
  });

  // runScript пишет пачкой: без разбора resultMeta.edits его правки не попадали
  // бы в блок «изменённые файлы» вовсе — пользователь не увидел бы ни одного диффа.
  it('expands the edits array a runScript call returns', () => {
    const refs = getFileChangeRefs({
      name: 'runScript',
      status: 'OK',
      resultMeta: {
        filesRead: 3,
        edits: [
          { path: 'src/A.java', operation: 'edit', additions: 1, deletions: 1, diff: '@@ -1 +1 @@' },
          { path: 'src/B.java', operation: 'create', additions: 4, deletions: 0 },
        ],
      },
    });
    expect(refs.map((r) => r.path)).toEqual(['src/A.java', 'src/B.java']);
    expect(refs[1]).toMatchObject({ operation: 'create', additions: 4, deletions: 0, diff: null, status: 'OK' });
  });

  it('returns nothing for a runScript call that changed no files', () => {
    expect(getFileChangeRefs({ name: 'runScript', status: 'OK', resultMeta: { filesRead: 3 } })).toEqual([]);
  });

  it('registers every mutation tool', () => {
    expect(FILE_MUTATION_TOOLS.has('createFile')).toBe(true);
    expect(FILE_MUTATION_TOOLS.has('editFile')).toBe(true);
    expect(FILE_MUTATION_TOOLS.has('runScript')).toBe(true);
  });
});

describe('getDocChangeRef', () => {
  it('returns null for non-mutation tools and missing meta', () => {
    expect(getDocChangeRef(null)).toBeNull();
    expect(getDocChangeRef({ name: 'getDocument', resultMeta: { id: 1 } })).toBeNull();
    expect(getDocChangeRef({ name: 'createDocument' })).toBeNull();
    expect(getDocChangeRef({ name: 'createDocument', resultMeta: {} })).toBeNull();
  });

  it('surfaces parentId from resultMeta.parent so a create can target its folder scope', () => {
    const ref = getDocChangeRef({
      name: 'createDocument',
      status: 'OK',
      resultMeta: { id: 55, parent: 7, title: 'New doc', descriptionVersion: 1 },
    });
    expect(ref).toEqual({
      id: '55',
      parentId: 7,
      descriptionVersion: 1,
      title: 'New doc',
      action: 'createDocument',
      status: 'OK',
    });
  });

  it('defaults parentId to null for a root-level document', () => {
    const ref = getDocChangeRef({
      name: 'updateDocument',
      status: 'OK',
      resultMeta: { id: 3, descriptionVersion: 2 },
    });
    expect(ref.parentId).toBeNull();
  });

  it('registers the document mutation tools', () => {
    expect(DOC_MUTATION_TOOLS.has('createDocument')).toBe(true);
    expect(DOC_MUTATION_TOOLS.has('updateDocument')).toBe(true);
  });
});
