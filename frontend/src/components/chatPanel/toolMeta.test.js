import { getFileChangeRef, getDocChangeRef, FILE_MUTATION_TOOLS, DOC_MUTATION_TOOLS } from './toolMeta';

describe('getFileChangeRef', () => {
  it('returns null for non-mutation tools and missing meta', () => {
    expect(getFileChangeRef(null)).toBeNull();
    expect(getFileChangeRef({ name: 'getFileContent', resultMeta: { path: 'a.txt' } })).toBeNull();
    expect(getFileChangeRef({ name: 'editFile' })).toBeNull();
    expect(getFileChangeRef({ name: 'editFile', resultMeta: {} })).toBeNull();
  });

  it('maps editFile meta to a change ref', () => {
    const ref = getFileChangeRef({
      name: 'editFile',
      status: 'OK',
      resultMeta: { path: 'src/App.java', operation: 'edit', additions: 2, deletions: 1, diff: '@@ -1 +1 @@' },
    });
    expect(ref).toEqual({
      path: 'src/App.java',
      operation: 'edit',
      additions: 2,
      deletions: 1,
      diff: '@@ -1 +1 @@',
      status: 'OK',
    });
  });

  it('defaults numbers and diff for createFile without diff', () => {
    const ref = getFileChangeRef({
      name: 'createFile',
      status: 'OK',
      resultMeta: { path: 'new.txt', operation: 'create' },
    });
    expect(ref.operation).toBe('create');
    expect(ref.additions).toBe(0);
    expect(ref.deletions).toBe(0);
    expect(ref.diff).toBeNull();
  });

  it('registers both mutation tools', () => {
    expect(FILE_MUTATION_TOOLS.has('createFile')).toBe(true);
    expect(FILE_MUTATION_TOOLS.has('editFile')).toBe(true);
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
