import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import gitApi from '@/api/gitApi';
import CommitHistory from './CommitHistory';
import { HISTORY_PAGE } from './useCommitHistory';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, params) => (params ? `${key} ${JSON.stringify(params)}` : key),
    i18n: { language: 'ru' },
  }),
}));

vi.mock('@/api/gitApi', () => ({
  default: {
    getCommits: vi.fn(),
    getOutgoing: vi.fn(),
    getCommit: vi.fn(),
  },
}));

const commit = (hash, message, date = '2026-10-02T12:00:00Z') => ({
  hash: `${hash}${'0'.repeat(33)}`,
  shortHash: hash,
  author: 'trialiya',
  date,
  message,
});

const NEW = commit('aaaaaaa', 'Новый коммит');
const OLD = commit('bbbbbbb', 'Старый коммит', '2026-09-30T12:00:00Z');

const show = (props = {}) =>
  render(
    <CommitHistory
      project="kb"
      rev=""
      path=""
      commit=""
      refreshToken={0}
      refsToken={0}
      onOpenFile={vi.fn()}
      onOpenSnapshot={vi.fn()}
      {...props}
    />,
  );

beforeEach(() => {
  vi.clearAllMocks();
  gitApi.getCommits.mockResolvedValue({ commits: [NEW, OLD], truncated: false });
  gitApi.getOutgoing.mockResolvedValue([NEW]);
  gitApi.getCommit.mockResolvedValue({
    ...NEW,
    files: [{ status: 'M', path: 'src/a.js', additions: 2, deletions: 1 }],
  });
});

describe('CommitHistory', () => {
  test('lists commits by day and marks the ones not pushed yet', async () => {
    show();

    const plate = await screen.findByText('Новый коммит');
    expect(screen.getByText('Старый коммит')).toBeInTheDocument();
    expect(screen.getAllByRole('group').length).toBeGreaterThanOrEqual(2);
    expect(within(plate.closest('[role="treeitem"]')).getByText('history.outgoing')).toBeInTheDocument();
    expect(gitApi.getCommits).toHaveBeenCalledWith('', expect.objectContaining({ limit: HISTORY_PAGE }));
  });

  // Файлы коммита спрашиваются только по раскрытию — один запрос на коммит,
  // и повторное раскрытие его не повторяет.
  test('asks for a commit files only when its plate is opened, once', async () => {
    const onOpenFile = vi.fn();
    show({ onOpenFile });
    const plate = (await screen.findByText('Новый коммит')).closest('[role="treeitem"]');
    expect(gitApi.getCommit).not.toHaveBeenCalled();

    await userEvent.click(plate);
    expect(plate).toHaveAttribute('aria-expanded', 'true');
    await userEvent.click(await screen.findByText('a.js'));
    expect(onOpenFile).toHaveBeenCalledWith('src/a.js', NEW.hash);

    await userEvent.click(plate);
    await userEvent.click(plate);
    expect(gitApi.getCommit).toHaveBeenCalledTimes(1);
  });

  test('a failed files request offers a retry', async () => {
    gitApi.getCommit.mockRejectedValueOnce(new Error('503'));
    show();
    await userEvent.click((await screen.findByText('Новый коммит')).closest('[role="treeitem"]'));

    await userEvent.click(await screen.findByText('history.retry'));
    expect(await screen.findByText('a.js')).toBeInTheDocument();
    expect(gitApi.getCommit).toHaveBeenCalledTimes(2);
  });

  test('"show more" asks for the next page of the same walk and appends it', async () => {
    gitApi.getCommits
      .mockResolvedValueOnce({ commits: [NEW], truncated: true })
      .mockResolvedValueOnce({ commits: [OLD], truncated: false });
    show();

    await userEvent.click(await screen.findByText('history.more'));

    expect(await screen.findByText('Старый коммит')).toBeInTheDocument();
    expect(gitApi.getCommits).toHaveBeenLastCalledWith('', expect.objectContaining({ skip: 1 }));
    expect(screen.queryByText('history.more')).not.toBeInTheDocument();
  });

  // Сужение — своё состояние: клик по файлу коммита меняет открытый путь, но
  // не перестраивает ленту, из которой по нему кликнули.
  test('narrows the history to the open path only on request', async () => {
    const { rerender } = show({ path: 'src/a.js' });
    await screen.findByText('Новый коммит');

    await userEvent.click(screen.getByText(/history.scopeOnly/));
    await waitFor(() => expect(gitApi.getCommits).toHaveBeenLastCalledWith('src/a.js', expect.anything()));

    const calls = gitApi.getCommits.mock.calls.length;
    rerender(
      <CommitHistory
        project="kb"
        rev=""
        path="src/b.js"
        commit=""
        refreshToken={0}
        refsToken={0}
        onOpenFile={vi.fn()}
        onOpenSnapshot={vi.fn()}
      />,
    );
    expect(gitApi.getCommits.mock.calls.length).toBe(calls);
  });

  test('a snapshot history does not ask what a push would send', async () => {
    show({ rev: 'v1' });
    await screen.findByText('Новый коммит');
    expect(gitApi.getOutgoing).not.toHaveBeenCalled();
    expect(screen.queryByText('history.outgoing')).not.toBeInTheDocument();
  });
});
