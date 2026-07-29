import { renderHook, waitFor, act } from '@testing-library/react';
import useFileTree from './useFileTree';
import { resetFileTreeCache, invalidatePath } from './fileTreeStore';
import gitApi from '../../api/gitApi';

vi.mock('../../api/gitApi');

const nodeA = { path: 'a', name: 'a', type: 'directory', size: null };
const nodeAB = { path: 'a/b', name: 'b', type: 'directory', size: null };
const nodeC = { path: 'a/b/c.txt', name: 'c.txt', type: 'file', size: 2 };

/** Ответ /api/git/browse для 'a/b/c.txt' — с листингами предков или без них. */
const fileView = (withTree = true) => ({
  path: 'a/b/c.txt',
  type: 'file',
  file: { path: 'a/b/c.txt', content: 'hi' },
  nodes: null,
  tree: withTree
    ? [
        { path: '', nodes: [nodeA] },
        { path: 'a', nodes: [nodeAB] },
        { path: 'a/b', nodes: [nodeC] },
      ]
    : [],
});

describe('useFileTree', () => {
  beforeEach(() => {
    resetFileTreeCache();
  });

  afterEach(() => {
    vi.resetAllMocks();
  });

  test('opens a deep-linked path with a single request and expands its ancestors', async () => {
    gitApi.browse.mockResolvedValue(fileView());

    const { result } = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn() }));

    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    // Раньше на каждый уровень вложенности уходил свой /tree, и только потом
    // запрос содержимого — здесь всё приходит одним ответом.
    expect(gitApi.browse).toHaveBeenCalledTimes(1);
    expect(gitApi.browse).toHaveBeenCalledWith('a/b/c.txt', true);
    expect(gitApi.getTree).not.toHaveBeenCalled();
    expect(gitApi.getFileContent).not.toHaveBeenCalled();

    expect(result.current.expanded.has('')).toBe(true);
    expect(result.current.expanded.has('a')).toBe(true);
    expect(result.current.expanded.has('a/b')).toBe(true);
    expect(result.current.treeCache['a/b']).toEqual([nodeC]);
    expect(result.current.content).toEqual({
      type: 'file',
      path: 'a/b/c.txt',
      file: { path: 'a/b/c.txt', content: 'hi' },
    });
  });

  test('a directory listing lands in the tree cache under its own path', async () => {
    gitApi.browse.mockResolvedValue({
      path: 'a/b',
      type: 'directory',
      file: null,
      nodes: [nodeC],
      tree: [
        { path: '', nodes: [nodeA] },
        { path: 'a', nodes: [nodeAB] },
      ],
    });

    const { result } = renderHook(() => useFileTree({ path: 'a/b', onPathChange: vi.fn() }));
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    expect(result.current.content).toEqual({ type: 'directory', path: 'a/b', nodes: [nodeC] });
    // Раскрытый каталог уже в кэше — второго запроса за тем же листингом нет.
    expect(result.current.expanded.has('a/b')).toBe(true);
    expect(result.current.treeCache['a/b']).toEqual([nodeC]);
  });

  test('a missing path resolves to not-found instead of an error', async () => {
    gitApi.browse.mockResolvedValue({ path: 'a/gone.txt', type: 'missing', file: null, nodes: null, tree: [] });

    const { result } = renderHook(() => useFileTree({ path: 'a/gone.txt', onPathChange: vi.fn() }));
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    expect(result.current.content).toEqual({ type: 'not-found', path: 'a/gone.txt' });
  });

  test('already-cached ancestors are not requested again on the next navigation', async () => {
    gitApi.browse.mockResolvedValue(fileView());
    const { result, rerender } = renderHook(({ path }) => useFileTree({ path, onPathChange: vi.fn() }), {
      initialProps: { path: 'a/b/c.txt' },
    });
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    gitApi.browse.mockResolvedValue({
      path: 'a/b/d.txt',
      type: 'file',
      file: { path: 'a/b/d.txt', content: 'yo' },
      nodes: null,
      tree: [],
    });
    rerender({ path: 'a/b/d.txt' });
    await waitFor(() => expect(result.current.content?.path).toBe('a/b/d.txt'));

    expect(gitApi.browse).toHaveBeenLastCalledWith('a/b/d.txt', false);
    // Дерево от прошлого пути никуда не делось.
    expect(result.current.treeCache['a/b']).toEqual([nodeC]);
  });

  test('the tree cache survives unmounting the panel (leaving the section and coming back)', async () => {
    gitApi.browse.mockResolvedValue(fileView());
    const first = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn() }));
    await waitFor(() => expect(first.result.current.contentLoading).toBe(false));
    first.unmount();

    gitApi.browse.mockResolvedValue(fileView(false));
    const second = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn() }));

    // Дерево нарисовано ещё до ответа — из модульного кэша.
    expect(second.result.current.treeCache['a/b']).toEqual([nodeC]);
    expect(second.result.current.expanded.has('a/b')).toBe(true);
    await waitFor(() => expect(second.result.current.contentLoading).toBe(false));
    expect(gitApi.browse).toHaveBeenLastCalledWith('a/b/c.txt', false);
  });

  test('a shared ancestor does not flicker when a second navigation starts before the first resolves', async () => {
    let resolveFirst;
    let resolveSecond;
    const firstPromise = new Promise((resolve) => {
      resolveFirst = resolve;
    });
    const secondPromise = new Promise((resolve) => {
      resolveSecond = resolve;
    });
    gitApi.browse.mockImplementationOnce(() => firstPromise).mockImplementationOnce(() => secondPromise);

    const { result, rerender } = renderHook(({ path }) => useFileTree({ path, onPathChange: vi.fn() }), {
      initialProps: { path: 'a/b/c.txt' },
    });
    await waitFor(() => expect(gitApi.browse).toHaveBeenCalledTimes(1));

    // Second navigation to a sibling file (same ancestors) fires before the
    // first request settles — both effects are now fetching 'a/b' (among
    // others).
    rerender({ path: 'a/b/d.txt' });
    await waitFor(() => expect(gitApi.browse).toHaveBeenCalledTimes(2));
    expect(result.current.loadingDirs.has('a/b')).toBe(true);

    // The stale first request resolves. Its cleanup must NOT clear the
    // spinner for 'a/b' — the second (current) request still owns it.
    await act(async () => {
      resolveFirst(fileView());
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.loadingDirs.has('a/b')).toBe(true);
    expect(result.current.contentLoading).toBe(true);

    // The current request resolves — now the spinner clears for real.
    await act(async () => {
      resolveSecond({
        path: 'a/b/d.txt',
        type: 'file',
        file: { path: 'a/b/d.txt', content: 'yo' },
        nodes: null,
        tree: [
          { path: '', nodes: [nodeA] },
          { path: 'a', nodes: [nodeAB] },
          { path: 'a/b', nodes: [nodeC] },
        ],
      });
    });
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    expect(result.current.loadingDirs.has('a/b')).toBe(false);
    expect(result.current.content).toEqual({
      type: 'file',
      path: 'a/b/d.txt',
      file: { path: 'a/b/d.txt', content: 'yo' },
    });
  });

  test('a failed directory load is not cached, so retrying refetches instead of staying empty', async () => {
    gitApi.browse.mockResolvedValue({ path: '', type: 'directory', file: null, nodes: [], tree: [] });
    gitApi.getTree.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => useFileTree({ path: '', onPathChange: vi.fn() }));
    await waitFor(() => expect(result.current.contentLoading).toBe(false));

    act(() => result.current.toggleExpand('broken'));
    await waitFor(() => expect(result.current.loadingDirs.has('broken')).toBe(false));

    // The rejected promise must not poison the cache — an empty cache entry
    // would be indistinguishable from a genuinely empty directory.
    expect(result.current.treeCache['broken']).toBeUndefined();

    gitApi.getTree.mockResolvedValue([{ path: 'broken/ok.txt', name: 'ok.txt', type: 'file', size: 1 }]);

    // Second click: same dir, toggles `expanded` back off, but must still retry the fetch.
    act(() => result.current.toggleExpand('broken'));
    await waitFor(() => expect(result.current.loadingDirs.has('broken')).toBe(false));

    expect(result.current.treeCache['broken']).toEqual([
      { path: 'broken/ok.txt', name: 'ok.txt', type: 'file', size: 1 },
    ]);
  });

  test('invalidatePath evicts an already-cached ancestor, so the next mount re-fetches it', async () => {
    gitApi.browse.mockResolvedValue(fileView());
    const first = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn() }));
    await waitFor(() => expect(first.result.current.contentLoading).toBe(false));
    expect(first.result.current.treeCache['a/b']).toEqual([nodeC]);
    first.unmount();

    // A chat-driven edit under 'a/b' invalidates that directory (and its
    // ancestors) from outside React, same as App.jsx does on a file mutation.
    invalidatePath('a/b/new.txt');

    gitApi.browse.mockResolvedValue(fileView(false));
    const second = renderHook(() => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn() }));
    await waitFor(() => expect(second.result.current.contentLoading).toBe(false));

    // Unlike the "survives unmounting" test above, 'a/b' was evicted, so this
    // mount must ask the server for ancestors again instead of trusting cache.
    expect(gitApi.browse).toHaveBeenLastCalledWith('a/b/c.txt', true);
  });

  test('bumping refreshToken re-fetches the currently open path even though it did not change', async () => {
    gitApi.browse.mockResolvedValue(fileView());
    const { result, rerender } = renderHook(
      ({ refreshToken }) => useFileTree({ path: 'a/b/c.txt', onPathChange: vi.fn(), refreshToken }),
      { initialProps: { refreshToken: 0 } },
    );
    await waitFor(() => expect(result.current.contentLoading).toBe(false));
    expect(gitApi.browse).toHaveBeenCalledTimes(1);

    gitApi.browse.mockResolvedValue({
      path: 'a/b/c.txt',
      type: 'file',
      file: { path: 'a/b/c.txt', content: 'edited by chat' },
      nodes: null,
      tree: [],
    });
    rerender({ refreshToken: 1 });

    await waitFor(() => expect(gitApi.browse).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(result.current.content).toEqual({
        type: 'file',
        path: 'a/b/c.txt',
        file: { path: 'a/b/c.txt', content: 'edited by chat' },
      }),
    );
  });
});
