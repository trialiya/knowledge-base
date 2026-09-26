import { renderHook, waitFor } from '@testing-library/react';
import useChangeDiff from './useChangeDiff';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

describe('useChangeDiff', () => {
  afterEach(() => vi.resetAllMocks());

  test('in the working tree the change is the uncommitted one', async () => {
    const entry = { status: 'M', path: 'a.js', patch: '@@' };
    gitApi.getStatus.mockResolvedValue([entry]);

    const { result } = renderHook(() => useChangeDiff({ project: 'kb', path: 'a.js', enabled: true }));

    await waitFor(() => expect(result.current.entry).toEqual(entry));
    expect(gitApi.getStatus).toHaveBeenCalledWith(expect.objectContaining({ path: 'a.js', patch: true }));
    expect(gitApi.getCommit).not.toHaveBeenCalled();
  });

  /** У снимка незакоммиченного не бывает: изменение — то, что сделал сам коммит. */
  test('in a snapshot the change is the one the commit made', async () => {
    const entry = { status: 'D', path: 'a.js', patch: '@@' };
    gitApi.getCommit.mockResolvedValue({ hash: 'abc', files: [entry] });

    const { result } = renderHook(() => useChangeDiff({ project: 'kb', path: 'a.js', rev: 'v1', enabled: true }));

    await waitFor(() => expect(result.current.entry).toEqual(entry));
    expect(gitApi.getCommit).toHaveBeenCalledWith('v1', expect.objectContaining({ path: 'a.js', patch: true }));
    expect(gitApi.getStatus).not.toHaveBeenCalled();
    expect(result.current.rev).toBe('v1');
  });

  test('a file the commit did not touch has no change, which is not an error', async () => {
    gitApi.getCommit.mockResolvedValue({ hash: 'abc', files: [] });

    const { result } = renderHook(() => useChangeDiff({ project: 'kb', path: 'a.js', rev: 'v1', enabled: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.entry).toBeNull();
    expect(result.current.error).toBeNull();
  });
});
