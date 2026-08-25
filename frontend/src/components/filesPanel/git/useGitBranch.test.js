import { renderHook, waitFor, act } from '@testing-library/react';
import useGitBranch from './useGitBranch';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

const PROJECT = 'kb';

const status = (over = {}) => ({
  current: 'main',
  detached: false,
  unborn: false,
  upstream: 'origin/main',
  ahead: 0,
  behind: 0,
  branches: ['main'],
  ...over,
});

const caps = (over = {}) => ({ project: PROJECT, available: true, commands: true, push: false, ...over });

describe('useGitBranch', () => {
  afterEach(() => vi.resetAllMocks());

  test('asks for the branch state and the permissions together', async () => {
    gitApi.getBranches.mockResolvedValue(status());
    gitApi.getCapabilities.mockResolvedValue(caps());

    const { result } = renderHook(() => useGitBranch({ project: PROJECT }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.status.current).toBe('main');
    expect(result.current.capabilities.commands).toBe(true);
    expect(gitApi.getBranches).toHaveBeenCalledWith(expect.objectContaining({ project: PROJECT }));
    expect(gitApi.getCapabilities).toHaveBeenCalledWith(expect.objectContaining({ project: PROJECT }));
  });

  /**
   * Счётчик «позади» и существует ради fetch'а: команда обязана оставить
   * панель с обновлённым состоянием, а не с тем, что было до неё.
   */
  test('a fetch leaves the fresh state behind', async () => {
    gitApi.getBranches.mockResolvedValueOnce(status()).mockResolvedValueOnce(status({ behind: 2 }));
    gitApi.getCapabilities.mockResolvedValue(caps());
    gitApi.fetch.mockResolvedValue({ command: 'fetch', output: '', status: status({ behind: 2 }) });

    const { result } = renderHook(() => useGitBranch({ project: PROJECT }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(() => result.current.fetchRemote());

    await waitFor(() => expect(result.current.status.behind).toBe(2));
    expect(result.current.running).toBe(false);
  });

  /** Отказ git'а — дело панели: хук его не глотает, иначе показать было бы нечего. */
  test('a refused command reaches the caller', async () => {
    gitApi.getBranches.mockResolvedValue(status());
    gitApi.getCapabilities.mockResolvedValue(caps());
    const refusal = Object.assign(new Error('Permission denied (publickey)'), {
      reason: 'Permission denied (publickey)',
    });
    gitApi.fetch.mockRejectedValue(refusal);

    const { result } = renderHook(() => useGitBranch({ project: PROJECT }));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await expect(act(() => result.current.fetchRemote())).rejects.toThrow('Permission denied');
    expect(result.current.running).toBe(false);
  });

  /**
   * Строка ветки — не главное содержимое панели: репозиторий, который не
   * отвечает про ветки, всё ещё показывает дерево.
   */
  test('a failed read leaves no status rather than an error screen', async () => {
    gitApi.getBranches.mockRejectedValue(new Error('boom'));
    gitApi.getCapabilities.mockResolvedValue(caps());

    const { result } = renderHook(() => useGitBranch({ project: PROJECT }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.status).toBeNull();
    expect(result.current.error).toBeTruthy();
  });
});
