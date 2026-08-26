import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitOutputCard from './GitOutputCard';

const event = (over = {}) => ({
  command: 'pull',
  project: 'kb',
  ok: true,
  output: 'Updating a1b2c3d..e4f5a6b\nFast-forward\n 12 files changed',
  branch: 'main',
  ...over,
});

describe('GitOutputCard', () => {
  /**
   * Отказ — единственное, ради чего к карточке возвращаются, и разворачивать
   * его руками пришлось бы каждый раз. Успех молчит: «Fast-forward» на всю
   * ленту не стоит той высоты, которую занимает.
   */
  test('a refusal opens itself, a success stays folded', () => {
    const { unmount } = render(<GitOutputCard event={event()} />);
    expect(screen.queryByText(/Fast-forward/)).not.toBeInTheDocument();
    unmount();

    render(<GitOutputCard event={event({ ok: false, output: 'remote rejected' })} />);
    expect(screen.getByText(/remote rejected/)).toBeInTheDocument();
  });

  test('the folded output opens on demand', async () => {
    render(<GitOutputCard event={event()} />);

    await userEvent.click(screen.getByRole('button', { name: 'repo.expandOutput' }));

    expect(screen.getByText(/Fast-forward/)).toBeInTheDocument();
  });

  /**
   * У половины git-команд успех молчалив. Пустой чёрный прямоугольник читался
   * бы как потерянный вывод, поэтому вместо него — строка о том, что его нет.
   */
  test('a silent command says so instead of showing an empty terminal', () => {
    render(<GitOutputCard event={event({ output: '' })} />);

    expect(screen.queryByRole('button', { name: 'repo.expandOutput' })).not.toBeInTheDocument();
    expect(screen.getByText('repo.noOutput')).toBeInTheDocument();
  });

  test('the command and the branch it left behind are both named', () => {
    render(<GitOutputCard event={event({ command: 'switch feature/x', branch: 'feature/x' })} />);

    expect(screen.getByText('git switch feature/x')).toBeInTheDocument();
    expect(screen.getByText('feature/x')).toBeInTheDocument();
  });

  /** Копировать нечего у молчаливой команды — и кнопки у неё нет. */
  test('there is nothing to copy when git said nothing', () => {
    render(<GitOutputCard event={event({ output: '   ' })} />);

    expect(screen.queryByRole('button', { name: 'repo.copyOutput' })).not.toBeInTheDocument();
  });
});
