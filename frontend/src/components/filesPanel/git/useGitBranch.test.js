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

const caps = (over = {}) => ({
  project: PROJECT,
  available: true,
  commands: true,
  push: false,
  ...over,
});

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
   * fetch двигает только счётчики, и перечитывает их не сам хук: строк ветки в
   * приложении две, репозиторий у них один, и обновиться обязаны обе. Поэтому
   * хук поднимает общий сигнал, а возвращается ответ уже по новому `refsToken`.
   */
  test('a fetch raises the shared refs signal and the new token brings the counters', async () => {
    gitApi.getBranches.mockResolvedValueOnce(status()).mockResolvedValueOnce(status({ behind: 2 }));
    gitApi.getCapabilities.mockResolvedValue(caps());
    gitApi.fetch.mockResolvedValue({ command: 'fetch', output: '', status: status({ behind: 2 }) });
    const onRefsChanged = vi.fn();

    const { result, rerender } = renderHook((p) => useGitBranch(p), {
      initialProps: { project: PROJECT, refsToken: 0, onRefsChanged },
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(() => result.current.fetchRemote());

    expect(onRefsChanged).toHaveBeenCalledTimes(1);
    expect(result.current.running).toBe(false);

    rerender({ project: PROJECT, refsToken: 1, onRefsChanged });
    await waitFor(() => expect(result.current.status.behind).toBe(2));
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

  /**
   * Одна осечка сети посреди работы не отменяет того, что мы про репозиторий уже знаем: обнулив
   * ответ, панель чата потеряла бы вкладку «Репозиторий» (её нет без capabilities), а открытая
   * модалка команд опустела бы — до следующего внешнего сигнала, которого может не быть часами.
   */
  test('a failed refetch keeps the last known state of the same project', async () => {
    gitApi.getBranches.mockResolvedValue(status());
    gitApi.getCapabilities.mockResolvedValue(caps());

    const { result, rerender } = renderHook((p) => useGitBranch(p), {
      initialProps: { project: PROJECT, refreshToken: 0 },
    });
    await waitFor(() => expect(result.current.status).not.toBeNull());

    gitApi.getBranches.mockRejectedValue(new Error('boom'));
    rerender({ project: PROJECT, refreshToken: 1 });

    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.status).not.toBeNull();
    expect(result.current.capabilities).not.toBeNull();
  });

  /** Но только того же репозитория: ветка проекта A под выбранным B — не устаревание, а ложь. */
  test('a failed read after a project change keeps nothing', async () => {
    gitApi.getBranches.mockResolvedValue(status());
    gitApi.getCapabilities.mockResolvedValue(caps());

    const { result, rerender } = renderHook((p) => useGitBranch(p), {
      initialProps: { project: PROJECT, refreshToken: 0 },
    });
    await waitFor(() => expect(result.current.status).not.toBeNull());

    gitApi.getBranches.mockRejectedValue(new Error('boom'));
    rerender({ project: 'other', refreshToken: 0 });

    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.status).toBeNull();
    expect(result.current.capabilities).toBeNull();
  });
});
