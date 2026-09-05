import { renderHook, act } from '@testing-library/react';
import useGitActions from './useGitActions';

const gitStub = (over = {}) => ({
  status: { current: 'main', upstream: 'origin/main' },
  running: false,
  commit: vi.fn(() => Promise.resolve({ command: 'commit' })),
  push: vi.fn(() => Promise.resolve({ command: 'push' })),
  switchBranch: vi.fn(() => Promise.resolve({ command: 'switch' })),
  discard: vi.fn(() => Promise.resolve({ command: 'restore' })),
  ...over,
});

const setup = (over = {}) => {
  const git = gitStub(over);
  const notify = vi.fn();
  const onRepoChanged = vi.fn();
  const hook = renderHook(() =>
    useGitActions({ git, project: 'kb', refreshToken: 3, onRepoChanged, notify, t: (key) => key }),
  );
  return { git, notify, onRepoChanged, ...hook };
};

describe('useGitActions', () => {
  /**
   * Коммит и push из панели «Файлы» — это те же два окна, что в чате, и
   * запускаются они не нажатием пункта меню, а кнопкой в окне: между «хочу
   * закоммитить» и самим коммитом лежит выбор файлов и сообщение.
   */
  test('the menu opens the dialog instead of running the command', () => {
    const { result, git } = setup();

    act(() => result.current.askCommit());
    expect(result.current.dialog).toBe('commit');
    expect(git.commit).not.toHaveBeenCalled();

    act(() => result.current.askPush());
    expect(result.current.dialog).toBe('push');
    expect(git.push).not.toHaveBeenCalled();
  });

  /** Окна читают состояние репозитория по тому же контракту, что и в чате. */
  test('the dialogs are handed the repository they command', () => {
    const { result } = setup();

    expect(result.current.dialogGit).toMatchObject({
      project: 'kb',
      refreshToken: 3,
      disabled: false,
      disabledReason: null,
      status: { current: 'main' },
    });
  });

  /** Идущая команда — единственный запрет панели, и она его называет. */
  test('a command already running blocks the dialog and says so', () => {
    const { result } = setup({ running: true });

    expect(result.current.dialogGit.disabled).toBe(true);
    expect(result.current.dialogGit.disabledReason).toBe('running');
  });

  /**
   * Отказ коммита остаётся в окне: набранное сообщение и выбор файлов там же, и
   * модалка с ошибкой поверх закрыла бы ровно то, ради чего в окно возвращаются.
   */
  test('a refused commit stays in the dialog, not in the panel notice', async () => {
    const { result, notify, onRepoChanged } = setup({
      commit: vi.fn(() => Promise.reject({ reason: 'pre-commit hook failed' })),
    });

    await act(async () => {
      expect(await result.current.dialogGit.commit('msg', ['a.js'])).toBeNull();
    });

    expect(notify).not.toHaveBeenCalled();
    expect(onRepoChanged).toHaveBeenCalled();
    expect(result.current.dialogGit.failure).toEqual({ command: 'commit', reason: 'pre-commit hook failed' });
  });

  /** Закрытое окно уносит отказ с собой: к следующей попытке он не относится. */
  test('closing the dialog forgets the refusal', async () => {
    const { result } = setup({ push: vi.fn(() => Promise.reject({ reason: 'rejected' })) });

    await act(async () => {
      await result.current.dialogGit.push();
    });
    expect(result.current.dialogGit.failure).not.toBeNull();

    act(() => result.current.closeDialog());
    expect(result.current.dialog).toBeNull();
    expect(result.current.dialogGit.failure).toBeNull();
  });

  /**
   * Push к недоступному remote отвечает через десяток секунд — окно к тому
   * времени могут закрыть. Такой отказ умирает вместе со своим окном: показать
   * его в следующем открытом значило бы обвинить в нём чужую команду.
   */
  test('a refusal that outlived its dialog does not surface in the next one', async () => {
    let refuse;
    const { result } = setup({ push: vi.fn(() => new Promise((_, reject) => (refuse = reject))) });

    act(() => result.current.askPush());
    let pushed;
    act(() => {
      pushed = result.current.dialogGit.push();
    });
    act(() => result.current.closeDialog());
    act(() => result.current.askCommit());

    // Ответ приходит уже в открытое окно коммита — и оно о нём знать не должно.
    await act(async () => {
      refuse({ reason: 'Permission denied (publickey)' });
      await pushed;
    });

    expect(result.current.dialogGit.failure).toBeNull();
  });

  /**
   * Быстрые команды показывают отказ по-прежнему уведомлением панели: своего
   * окна, в котором его можно было бы прочесть, у них нет.
   */
  test('the quick commands still refuse through the panel notice', async () => {
    const { result, notify } = setup({ discard: vi.fn(() => Promise.reject({ reason: 'permission denied' })) });

    act(() => result.current.askDiscard('a.js'));
    await act(async () => result.current.confirmDiscard());

    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ params: { reason: 'permission denied' } }));
  });

  /** Имя новой ветки — единственное, что осталось однострочному запросу. */
  test('only the new branch is still asked for in one line', async () => {
    const { result, git } = setup();

    act(() => result.current.askNewBranch());
    expect(result.current.naming).toBe(true);

    await act(async () => result.current.confirmNewBranch('feature/x'));
    expect(git.switchBranch).toHaveBeenCalledWith('feature/x', true);
    expect(result.current.naming).toBe(false);
  });
});
