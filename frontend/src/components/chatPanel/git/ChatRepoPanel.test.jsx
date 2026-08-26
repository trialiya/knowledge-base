import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ChatRepoPanel from './ChatRepoPanel';

const git = ({ status, capabilities, ...rest } = {}) => ({
  loading: false,
  changes: [],
  last: null,
  disabled: false,
  ...rest,
  status: {
    current: 'main',
    detached: false,
    unborn: false,
    upstream: 'origin/main',
    ahead: 0,
    behind: 0,
    merging: false,
    conflicts: [],
    ...status,
  },
  capabilities: { commands: true, push: true, ...capabilities },
});

describe('ChatRepoPanel', () => {
  /**
   * Вкладка показывает, модалка делает: единственная кнопка здесь — дверь в неё.
   * Как только кнопок станет две, вкладка начнёт спорить с модалкой за то, где
   * запускают команды.
   */
  test('offers exactly one way to act — the door into the commands dialog', async () => {
    const onOpenCommands = vi.fn();
    render(<ChatRepoPanel git={git({ status: { merging: true } })} onOpenCommands={onOpenCommands} />);

    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]);
    expect(onOpenCommands).toHaveBeenCalled();
  });

  test('the door is shut while the assistant is working', () => {
    render(<ChatRepoPanel git={git({ disabled: true })} onOpenCommands={vi.fn()} />);

    expect(screen.getByRole('button', { name: /repo.commands/ })).toBeDisabled();
  });

  /**
   * Ноль не показываем: строка отвечает «разошлись ли», и два нуля рядом с
   * веткой читают на каждом открытии панели, ничего из них не узнавая.
   */
  test('the counters appear only once the branch has actually diverged', () => {
    const { rerender } = render(<ChatRepoPanel git={git()} onOpenCommands={vi.fn()} />);
    expect(screen.queryByText(/↑|↓/)).not.toBeInTheDocument();

    rerender(<ChatRepoPanel git={git({ status: { ahead: 2, behind: 1 } })} onOpenCommands={vi.fn()} />);
    expect(screen.getByText('↑2')).toBeInTheDocument();
    expect(screen.getByText('↓1')).toBeInTheDocument();
  });

  /** Незавершённый merge — единственное, о чём вкладка говорит без просьбы. */
  test('an unfinished merge is announced, conflicts counted', () => {
    render(
      <ChatRepoPanel git={git({ status: { merging: true, conflicts: ['a.js', 'b.js'] } })} onOpenCommands={vi.fn()} />,
    );

    expect(screen.getByRole('status')).toHaveTextContent('files:git.mergeConflicts');
  });

  test('a project without git commands gets no door at all', () => {
    render(<ChatRepoPanel git={git({ capabilities: { commands: false } })} onOpenCommands={vi.fn()} />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  /** Одна строка про последнюю команду, не журнал: вывод целиком лежит в ленте. */
  test('the last command is one line and carries its outcome', () => {
    render(
      <ChatRepoPanel git={git({ last: { command: 'pull', ok: false } })} onOpenCommands={vi.fn()} />,
    );

    expect(screen.getByText('pull')).toBeInTheDocument();
    expect(screen.getByText('repo.outcomeFailed')).toBeInTheDocument();
  });
});
