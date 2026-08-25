import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitMenu from './GitMenu';

const status = (over = {}) => ({
  current: 'main',
  branches: ['main', 'feature/x'],
  dirty: false,
  upstream: 'origin/main',
  ahead: 0,
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

  /** push — отдельное право: без него пункта нет вовсе, сколько бы своих коммитов ни было. */
  test('push appears only with the push grant', async () => {
    const onPush = vi.fn();
    const { rerender } = render(
      <GitMenu status={status({ ahead: 2 })} capabilities={{ commands: true }} onPush={onPush} />,
    );

    await open();
    expect(screen.queryByRole('menuitem', { name: 'git.push' })).not.toBeInTheDocument();

    rerender(<GitMenu status={status({ ahead: 2 })} capabilities={{ commands: true, push: true }} onPush={onPush} />);
    await userEvent.click(screen.getByRole('menuitem', { name: 'git.push' }));

    expect(onPush).toHaveBeenCalled();
  });

  /** Ветке без upstream неоткуда втягивать — и push ей разрешён: он и заведёт upstream. */
  test('pull needs an upstream, the first push does not', async () => {
    render(<GitMenu status={status({ upstream: null, ahead: 1 })} capabilities={{ commands: true, push: true }} />);

    await open();
    expect(screen.getByRole('menuitem', { name: 'git.pull' })).toBeDisabled();
    expect(screen.getByRole('menuitem', { name: 'git.push' })).toBeEnabled();
  });

  test('a running command blocks the trigger', () => {
    render(<GitMenu status={status()} running />);

    expect(screen.getByRole('button', { name: 'git.menu' })).toBeDisabled();
  });
});
