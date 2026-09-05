import { act, renderHook, waitFor } from '@testing-library/react';
import useChatGit from './useChatGit';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi', () => ({
  default: {
    getBranches: vi.fn(),
    getCapabilities: vi.fn(),
    getStatus: vi.fn(),
    fetch: vi.fn(),
    pull: vi.fn(),
    push: vi.fn(),
    switchBranch: vi.fn(),
    stashPush: vi.fn(),
    stashPop: vi.fn(),
    commit: vi.fn(),
    discard: vi.fn(),
    abortMerge: vi.fn(),
  },
}));

const status = { current: 'main', branches: ['main'], dirty: true, merging: false, conflicts: [] };
const capabilities = { project: 'kb', available: true, commands: true, push: true };

const ready = async (props = {}) => {
  const hook = renderHook((p) => useChatGit(p), {
    initialProps: { chatId: 'c-1', project: 'kb', refreshToken: 0, busy: false, visible: true, ...props },
  });
  await waitFor(() => expect(hook.result.current.status).not.toBeNull());
  return hook;
};

beforeEach(() => {
  vi.clearAllMocks();
  gitApi.getBranches.mockResolvedValue(status);
  gitApi.getCapabilities.mockResolvedValue(capabilities);
  gitApi.getStatus.mockResolvedValue([]);
});

describe('useChatGit', () => {
  /**
   * Главное правило: пока модель работает, команды не запускаются. Проверяется
   * здесь, а не на пропсах компонентов — компоненту `disabled` передают готовым,
   * и цепочка, которая его решает, ни в одном из тех тестов не участвует.
   */
  test('the assistant working shuts the commands, and says which of the three reasons it is', async () => {
    const { result } = await ready({ busy: true });

    expect(result.current.disabled).toBe(true);
    expect(result.current.disabledReason).toBe('busy');
  });

  /** Черновику записывать некуда: id ему выдумал фронт, чата на бэкенде нет. */
  test('a chat that does not exist yet gets no commands, for its own reason', async () => {
    const { result } = await ready({ chatId: null });

    expect(result.current.disabled).toBe(true);
    expect(result.current.disabledReason).toBe('draft');
  });

  test('an idle chat with a real id may run them', async () => {
    const { result } = await ready();

    expect(result.current.disabled).toBe(false);
    expect(result.current.disabledReason).toBeNull();
  });

  /**
   * Команда несёт id чата — по нему бэкенд оставляет ряд в истории и отказывает
   * на занятом чате. Без него команда выполнилась бы, не оставив следа.
   */
  test('a command carries the chat it was run from; reading state does not', async () => {
    gitApi.commit.mockResolvedValue({ command: 'commit', output: '', status });
    const { result } = await ready();

    await act(() => result.current.commit('message', ['a.js']));

    expect(gitApi.commit).toHaveBeenCalledWith(
      'message',
      expect.objectContaining({ chat: 'c-1', project: 'kb', paths: ['a.js'] }),
    );
    expect(gitApi.getBranches).toHaveBeenCalledWith(expect.not.objectContaining({ chat: 'c-1' }));
  });

  /** Отказ остаётся здесь текстом самого git — по нему человек поймёт, что чинить. */
  test('a refusal keeps git own words and marks the last command failed', async () => {
    const denied = Object.assign(new Error('HTTP 422'), { reason: 'Permission denied (publickey)' });
    gitApi.push.mockRejectedValue(denied);
    const { result } = await ready();

    await act(() => result.current.push());

    expect(result.current.failure).toMatchObject({ command: 'push', reason: 'Permission denied (publickey)' });
    expect(result.current.last).toMatchObject({ command: 'push', ok: false });
  });

  /**
   * Отказ тоже мог сдвинуть дерево: отклонённый pre-commit hook оставляет за
   * собой уже застейдженные файлы. Сигнал уходит в обоих случаях.
   */
  test('the working tree is re-read after a refusal too, not only after a success', async () => {
    gitApi.commit.mockRejectedValue(Object.assign(new Error('hook'), { reason: 'hook declined' }));
    const onRepoChanged = vi.fn();
    const { result } = await ready({ onRepoChanged });

    await act(() => result.current.commit('message', []));

    expect(onRepoChanged).toHaveBeenCalled();
  });

  /**
   * Состояние остаётся на экране, пока идёт перезапрос: команда поднимает общий
   * сигнал обновления, и обнуление на это время гасило бы модалку, из которой
   * команду и запустили.
   */
  test('the branch state survives the refresh a command triggers', async () => {
    const { result, rerender } = await ready();

    let resolveNext;
    gitApi.getBranches.mockReturnValue(new Promise((r) => (resolveNext = r)));
    rerender({ chatId: 'c-1', project: 'kb', refreshToken: 1, busy: false });

    expect(result.current.loading).toBe(true);
    expect(result.current.status).toMatchObject({ current: 'main' });

    await act(async () => {
      resolveNext({ ...status, current: 'feature/x' });
    });
    await waitFor(() => expect(result.current.status.current).toBe('feature/x'));
  });

  /**
   * Ответ другого проекта — не устаревший, а чужой. Показать ветку проекта A
   * под выбранным B значило бы предложить в модалке переключение на ветку,
   * которой у B нет, — и отправить команду туда, где её не ждали.
   */
  test('switching to another project blanks the state instead of keeping the old one', async () => {
    const { result, rerender } = await ready();
    expect(result.current.status.current).toBe('main');

    let resolveB;
    gitApi.getBranches.mockReturnValue(new Promise((r) => (resolveB = r)));
    rerender({ chatId: 'c-1', project: 'other', refreshToken: 0, busy: false });

    expect(result.current.status).toBeNull();
    expect(result.current.capabilities).toBeNull();

    await act(async () => {
      resolveB({ ...status, current: 'release' });
    });
    await waitFor(() => expect(result.current.status.current).toBe('release'));
  });

  /** Пока команда идёт, вторую не запускают — и причина у этого своя. */
  test('a command in flight is its own reason, not the assistant working', async () => {
    let finish;
    gitApi.push.mockReturnValue(new Promise((r) => (finish = r)));
    const { result } = await ready();

    act(() => {
      result.current.push();
    });
    await waitFor(() => expect(result.current.disabledReason).toBe('running'));

    await act(async () => {
      finish({ command: 'push', output: '', status });
    });
  });

  /**
   * Список несохранённого — отдельный запрос, а рисует его одна вкладка. Пока
   * её не открыли, спрашивать нечего; ветка и права нужны и закрытой — по ним
   * решается, быть ли вкладке вообще.
   */
  test('the uncommitted list is only asked for once the tab is open', async () => {
    const hook = await ready({ visible: false });
    expect(gitApi.getStatus).not.toHaveBeenCalled();
    expect(hook.result.current.capabilities).not.toBeNull();

    hook.rerender({ chatId: 'c-1', project: 'kb', refreshToken: 0, busy: false, visible: true });
    await waitFor(() => expect(gitApi.getStatus).toHaveBeenCalled());
  });

  /**
   * Из чата запускаются ровно две команды. Остальные — ветки, stash, pull, откат —
   * живут в панели «Файлы»: второй набор тех же кнопок обязан был бы с ней
   * разойтись, и этот тест — то, что не даёт им завестись здесь снова.
   */
  test('the chat runs exactly two commands: commit and push', async () => {
    const { result } = await ready();

    expect(typeof result.current.commit).toBe('function');
    expect(typeof result.current.push).toBe('function');
    ['fetch', 'pull', 'switchBranch', 'stashPush', 'stashPop', 'abortMerge', 'discard'].forEach((name) =>
      expect(result.current[name]).toBeUndefined(),
    );
  });

  /**
   * Проект и сигнал обновления уезжают вместе с состоянием: окно коммита само
   * спрашивает патч выбранного файла, окно push — список коммитов, и оба обязаны
   * спрашивать про тот же репозиторий и на том же тике, что и список рядом.
   */
  test('the state carries the project and the refresh signal the dialogs ask with', async () => {
    const { result } = await ready({ refreshToken: 7 });

    expect(result.current.project).toBe('kb');
    expect(result.current.refreshToken).toBe(7);
  });
});
