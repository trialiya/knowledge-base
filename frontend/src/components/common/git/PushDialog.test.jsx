import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PushDialog from './PushDialog';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi', () => ({ default: { getOutgoing: vi.fn() } }));

const commit = (hash, message) => ({
  hash,
  shortHash: hash.slice(0, 7),
  author: 'Test',
  email: 't@example.com',
  date: '2026-09-01T10:00:00Z',
  message,
});

const git = ({ status, ...rest } = {}) => ({
  project: 'kb',
  refreshToken: 0,
  disabled: false,
  failure: null,
  push: vi.fn(() => Promise.resolve({ command: 'push' })),
  ...rest,
  status: { current: 'main', upstream: 'origin/main', ahead: 2, ...status },
});

beforeEach(() => {
  vi.clearAllMocks();
  gitApi.getOutgoing.mockResolvedValue([commit('aaaaaaa1', 'second'), commit('bbbbbbb2', 'first')]);
});

describe('PushDialog', () => {
  /**
   * Смысл окна: перед отправкой видно, что именно уедет, — в том числе чужой
   * коммит, случайно оказавшийся на ветке.
   */
  test('lists what the push would publish, newest first', async () => {
    render(<PushDialog git={git()} onClose={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('second');
  });

  test('pushing closes the dialog only when git accepted it', async () => {
    const onClose = vi.fn();
    const state = git({ push: vi.fn(() => Promise.resolve(undefined)) });
    render(<PushDialog git={state} onClose={onClose} />);
    await waitFor(() => expect(screen.getByText('second')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /git.pushDialog.submit/ }));
    expect(state.push).toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  /** Нечего отправлять — кнопка не должна звать в сеть за пустым push. */
  test('an empty list disables the push', async () => {
    gitApi.getOutgoing.mockResolvedValue([]);
    render(<PushDialog git={git({ status: { ahead: 0 } })} onClose={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('git.nothingToPush')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /git.pushDialog.submit/ })).toBeDisabled();
  });

  /** Ветка без upstream: push её создаст, и окно говорит об этом до нажатия. */
  test('a branch that tracks nothing says where it will be created', async () => {
    render(<PushDialog git={git({ status: { upstream: null } })} onClose={vi.fn()} />);

    expect(screen.getByText('git.pushDialog.newBranch')).toBeInTheDocument();
  });
});
