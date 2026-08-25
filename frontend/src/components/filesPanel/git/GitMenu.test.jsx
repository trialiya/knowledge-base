import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitMenu from './GitMenu';

const status = (over = {}) => ({
  current: 'main',
  branches: ['main', 'feature/x'],
  dirty: false,
  ...over,
});

const open = async () => userEvent.click(screen.getByRole('button', { name: 'git.menu' }));

describe('GitMenu', () => {
  test('switches to another branch and never offers the current one', async () => {
    const onSwitch = vi.fn();
    render(<GitMenu status={status()} onSwitch={onSwitch} />);

    await open();
    expect(screen.getByRole('menuitem', { name: /main/ })).toBeDisabled();
    await userEvent.click(screen.getByRole('menuitem', { name: /feature\/x/ }));

    expect(onSwitch).toHaveBeenCalledWith('feature/x');
  });

  /**
   * Выключенный пункт вместо исчезнувшего: он вместе с подсказкой отвечает,
   * чего не хватает, а пропавший заставляет гадать, был ли он вообще.
   */
  test('stash and commit stay visible but disabled while the tree is clean', async () => {
    render(<GitMenu status={status({ dirty: false })} />);

    await open();
    expect(screen.getByRole('menuitem', { name: 'git.stash' })).toBeDisabled();
    expect(screen.getByRole('menuitem', { name: 'git.commit' })).toBeDisabled();
    // Вернуть из stash можно и на чистом дереве — ради этого его и прятали.
    expect(screen.getByRole('menuitem', { name: 'git.stashPop' })).toBeEnabled();
  });

  test('a dirty tree enables committing and stashing', async () => {
    const onCommit = vi.fn();
    render(<GitMenu status={status({ dirty: true })} onCommit={onCommit} />);

    await open();
    await userEvent.click(screen.getByRole('menuitem', { name: 'git.commit' }));

    expect(onCommit).toHaveBeenCalled();
    // Пункт закрывает меню за собой: следующий клик пользователя — уже в модалке.
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  test('a single-branch repository offers no switch list', async () => {
    render(<GitMenu status={status({ branches: ['main'] })} />);

    await open();
    expect(screen.queryByText('git.switchTo')).not.toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'git.newBranch' })).toBeInTheDocument();
  });

  test('a running command blocks the trigger', () => {
    render(<GitMenu status={status()} running />);

    expect(screen.getByRole('button', { name: 'git.menu' })).toBeDisabled();
  });
});
