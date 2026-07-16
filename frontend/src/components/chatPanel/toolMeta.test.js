import { getFileChangeRef, FILE_MUTATION_TOOLS } from './toolMeta';

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
