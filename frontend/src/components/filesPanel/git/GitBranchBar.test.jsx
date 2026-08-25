import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitBranchBar from './GitBranchBar';

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

describe('GitBranchBar', () => {
  test('shows the branch and no counters while nothing has drifted', () => {
    render(<GitBranchBar status={status()} capabilities={{ commands: false }} />);

    expect(screen.getByText('main')).toBeInTheDocument();
    expect(screen.queryByText(/[↑↓]/)).not.toBeInTheDocument();
  });

  test('shows how far the branch has drifted in both directions', () => {
    render(<GitBranchBar status={status({ ahead: 1, behind: 3 })} capabilities={{ commands: false }} />);

    expect(screen.getByText('↓3')).toBeInTheDocument();
    expect(screen.getByText('↑1')).toBeInTheDocument();
  });

  /** Права решают, есть ли кнопки: проект без git-команд показывает только ветку. */
  test('offers fetch only where the project permits commands', async () => {
    const onFetch = vi.fn();
    const { rerender } = render(
      <GitBranchBar status={status()} capabilities={{ commands: false }} onFetch={onFetch} />,
    );

    expect(screen.queryByRole('button')).not.toBeInTheDocument();

    rerender(<GitBranchBar status={status()} capabilities={{ commands: true }} onFetch={onFetch} />);
    await userEvent.click(screen.getByRole('button'));

    expect(onFetch).toHaveBeenCalled();
  });

  test('a command in flight disables its button', () => {
    render(<GitBranchBar status={status()} capabilities={{ commands: true }} running onFetch={vi.fn()} />);

    expect(screen.getByRole('button')).toBeDisabled();
  });

  /** Detached HEAD — состояние, а не имя ветки: панель обязана это сказать. */
  test('marks a detached head', () => {
    render(<GitBranchBar status={status({ detached: true, current: 'a1b2c3d' })} capabilities={{ commands: true }} />);

    expect(screen.getByText('a1b2c3d')).toBeInTheDocument();
    // Без инициализированного i18n `t` отдаёт ключ — метка состояния всё равно
    // отличима от имени ветки, а проверяем мы именно её наличие.
    expect(screen.getByText('git.detached')).toBeInTheDocument();
  });

  test('renders nothing before the state arrives', () => {
    const { container } = render(<GitBranchBar status={null} capabilities={null} />);

    expect(container).toBeEmptyDOMElement();
  });
});
