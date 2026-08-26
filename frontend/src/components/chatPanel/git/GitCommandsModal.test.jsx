import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitCommandsModal from './GitCommandsModal';

const status = (over = {}) => ({
  current: 'main',
  detached: false,
  unborn: false,
  upstream: 'origin/main',
  ahead: 0,
  behind: 0,
  branches: ['main', 'feature/x'],
  dirty: false,
  merging: false,
  conflicts: [],
  ...over,
});

const git = ({ status: over, capabilities, ...rest } = {}) => ({
  disabled: false,
  changes: [],
  last: null,
  failure: null,
  fetch: vi.fn(),
  pull: vi.fn(),
  push: vi.fn(),
  stashPush: vi.fn(),
  stashPop: vi.fn(),
  switchBranch: vi.fn(() => Promise.resolve()),
  commit: vi.fn(() => Promise.resolve()),
  abortMerge: vi.fn(),
  dismissFailure: vi.fn(),
  ...rest,
  status: status(over),
  capabilities: { commands: true, push: true, ...capabilities },
});

describe('GitCommandsModal', () => {
  /**
   * Главное правило модалки: пока модель работает, командой не выстрелишь.
   * Причина называется один раз на всю модалку — повторённая под каждой из
   * девяти кнопок, она читалась бы как девять разных запретов.
   */
  test('every command is off while the assistant is working, and the reason is said once', () => {
    render(<GitCommandsModal git={git({ disabled: true, status: { dirty: true } })} onClose={vi.fn()} />);

    for (const button of screen.getAllByRole('button')) {
      // Кроме «Закрыть»: выйти из модалки можно всегда.
      if (button.textContent === 'repo.close') continue;
      expect(button).toBeDisabled();
    }
    expect(screen.getAllByText('repo.busyHint')).toHaveLength(1);
  });

  /**
   * Недоступная команда объясняется, а не прячется: кнопка, исчезающая
   * из-под курсора, учит только тому, что интерфейсу нельзя доверять.
   */
  test('committing stays visible but disabled on a clean tree, with the reason spelled out', () => {
    render(<GitCommandsModal git={git()} onClose={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'files:git.commit' })).toBeDisabled();
    expect(screen.getByText('files:git.nothingToCommit')).toBeInTheDocument();
  });

  test('push is refused for a project without the grant, and says so', () => {
    render(<GitCommandsModal git={git({ capabilities: { commands: true, push: false } })} onClose={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'repo.push' })).toBeDisabled();
    expect(screen.getByText(/repo.pushNotAllowed/)).toBeInTheDocument();
  });

  test('a commit message reaches the command and the field empties afterwards', async () => {
    const state = git({ status: { dirty: true } });
    render(<GitCommandsModal git={state} onClose={vi.fn()} />);

    const field = screen.getByPlaceholderText('files:git.commitMessage');
    await userEvent.type(field, 'починил сборку');
    await userEvent.click(screen.getByRole('button', { name: 'files:git.commit' }));

    expect(state.commit).toHaveBeenCalledWith('починил сборку');
    expect(field).toHaveValue('');
  });

  /** Текущая ветка в списке переключения не предлагается: переключаться некуда. */
  test('the branch picker never offers the branch already checked out', () => {
    render(<GitCommandsModal git={git()} onClose={vi.fn()} />);

    const options = screen.getAllByRole('option').map((o) => o.textContent);
    expect(options).toContain('feature/x');
    expect(options).not.toContain('main');
  });

  /**
   * Выход из незавершённого merge — здесь же, где в него вошли: pull с
   * конфликтом иначе оказался бы тупиком.
   */
  test('an unfinished merge offers the way out of it', async () => {
    const state = git({ status: { merging: true, conflicts: ['a.js'] } });
    render(<GitCommandsModal git={state} onClose={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'files:git.abortMerge' }));

    expect(state.abortMerge).toHaveBeenCalled();
  });

  test('a refusal shows git own words rather than a status code', () => {
    render(
      <GitCommandsModal
        git={git({ failure: { command: 'push', reason: 'Permission denied (publickey)' } })}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByText(/Permission denied \(publickey\)/)).toBeInTheDocument();
  });
});
