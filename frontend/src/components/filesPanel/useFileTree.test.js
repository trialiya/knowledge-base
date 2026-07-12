import { renderHook, waitFor, act } from '@testing-library/react';
import useFileTree from './useFileTree';
import gitApi from '../../api/gitApi';

jest.mock('../../api/gitApi');

describe('useFileTree', () => {
  afterEach(() => {
    jest.resetAllMocks();
  });

  test('expands all ancestor directories for a deep-linked path', async () => {
    const tree = {
      '': [{ path: 'a', name: 'a', type: 'directory', size: null }],
      a: [{ path: 'a/b', name: 'b', type: 'directory', size: null }],
      'a/b': [{ path: 'a/b/c.txt', name: 'c.txt', type: 'file', size: 2 }],
    };
    gitApi.getTree.mockImplementation((dirPath) => Promise.resolve(tree[dirPath] ?? []));
    gitApi.getFileContent.mockResolvedValue({ path: 'a/b/c.txt', content: 'hi' });

    const { result } = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: jest.fn() }));

    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    expect(result.current.expanded.has('')).toBe(true);
    expect(result.current.expanded.has('a')).toBe(true);
    expect(result.current.expanded.has('a/b')).toBe(true);
    expect(result.current.content).toEqual({
      type: 'file',
      path: 'a/b/c.txt',
      file: { path: 'a/b/c.txt', content: 'hi' },
    });
  });

  test('a failed directory load is not cached, so retrying refetches instead of staying empty', async () => {
    gitApi.getTree.mockImplementation((dirPath) => {
      if (dirPath === '') return Promise.resolve([]);
      return Promise.reject(new Error('boom'));
    });
    gitApi.getFileContent.mockResolvedValue({ path: '', content: '' });

    const { result } = renderHook(() => useFileTree({ path: '', onPathChange: jest.fn() }));
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    act(() => result.current.toggleExpand('broken'));
    await waitFor(() => expect(result.current.loadingDirs.has('broken')).toBe(false));

    // The rejected promise must not poison the cache — an empty cache entry
    // would be indistinguishable from a genuinely empty directory.
    expect(result.current.treeCache['broken']).toBeUndefined();

    gitApi.getTree.mockImplementation((dirPath) => {
      if (dirPath === 'broken') {
        return Promise.resolve([{ path: 'broken/ok.txt', name: 'ok.txt', type: 'file', size: 1 }]);
      }
      return Promise.resolve([]);
    });

    // Second click: same dir, toggles `expanded` back off, but must still retry the fetch.
    act(() => result.current.toggleExpand('broken'));
    await waitFor(() => expect(result.current.loadingDirs.has('broken')).toBe(false));

    expect(result.current.treeCache['broken']).toEqual([
      { path: 'broken/ok.txt', name: 'ok.txt', type: 'file', size: 1 },
    ]);
  });
});
