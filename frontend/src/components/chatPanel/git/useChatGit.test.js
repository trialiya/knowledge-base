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
    initialProps: { chatId: 'c-1', project: 'kb', refreshToken: 0, busy: false, ...props },
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
    gitApi.pull.mockResolvedValue({ command: 'pull', output: '', status });
    const { result } = await ready();

    await act(() => result.current.pull());

    expect(gitApi.pull).toHaveBeenCalledWith(expect.objectContaining({ chat: 'c-1', project: 'kb' }));
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
   * Отказ тоже мог сдвинуть дерево: конфликтующий `stash pop` накладывает stash
   * и только потом отказывает. Сигнал уходит в обоих случаях.
   */
  test('the working tree is re-read after a refusal too, not only after a success', async () => {
    gitApi.stashPop.mockRejectedValue(Object.assign(new Error('conflict'), { reason: 'CONFLICT' }));
    const onRepoChanged = vi.fn();
    const { result } = await ready({ onRepoChanged });

    await act(() => result.current.stashPop());

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
    gitApi.fetch.mockReturnValue(new Promise((r) => (finish = r)));
    const { result } = await ready();

    act(() => {
      result.current.fetch();
    });
    await waitFor(() => expect(result.current.disabledReason).toBe('running'));

    await act(async () => {
      finish({ command: 'fetch', output: '', status });
    });
  });
});
