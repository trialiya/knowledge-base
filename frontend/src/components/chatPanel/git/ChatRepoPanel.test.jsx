import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ChatRepoPanel from './ChatRepoPanel';

vi.mock('@/navigation/fileNavigationBus', () => ({ navigateToFile: vi.fn() }));
import { navigateToFile } from '@/navigation/fileNavigationBus';

const change = (path, status = 'M') => ({ path, status, additions: 1, deletions: 0 });

const git = ({ status, capabilities, ...rest } = {}) => ({
  loading: false,
  changes: [],
  last: null,
  disabled: false,
  project: 'kb',
  ...rest,
  status: {
    current: 'main',
    detached: false,
    unborn: false,
    upstream: 'origin/main',
    ahead: 1,
    behind: 0,
    merging: false,
    conflicts: [],
    ...status,
  },
  capabilities: { commands: true, push: true, ...capabilities },
});

const dirty = (n) => Array.from({ length: n }, (_, i) => change(`src/file${i}.js`));

describe('ChatRepoPanel', () => {
  /**
   * Вкладка отвечает на три вопроса из чата и предлагает ровно два действия:
   * сохранить работу и опубликовать её. Третья кнопка означала бы, что в чате
   * снова заводится второй набор git-команд.
   */
  test('offers exactly two actions — commit and push', async () => {
    const onOpenCommit = vi.fn();
    const onOpenPush = vi.fn();
    render(<ChatRepoPanel git={git({ changes: dirty(1) })} onOpenCommit={onOpenCommit} onOpenPush={onOpenPush} />);

    await userEvent.click(screen.getByRole('button', { name: /repo.commit/ }));
    await userEvent.click(screen.getByRole('button', { name: /repo.push/ }));

    expect(onOpenCommit).toHaveBeenCalled();
    expect(onOpenPush).toHaveBeenCalled();
  });

  /** push — отдельное разрешение проекта: без него кнопки нет вовсе. */
  test('push is offered only where the project permits it', () => {
    render(
      <ChatRepoPanel
        git={git({ changes: dirty(1), capabilities: { push: false } })}
        onOpenCommit={vi.fn()}
        onOpenPush={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: /repo.push/ })).not.toBeInTheDocument();
  });

  test('both actions are shut while the assistant is working', () => {
    render(
      <ChatRepoPanel
        git={git({ changes: dirty(1), disabled: true, disabledReason: 'busy' })}
        onOpenCommit={vi.fn()}
        onOpenPush={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: /repo.commit/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /repo.push/ })).toBeDisabled();
  });

  /** Коммитить нечего — кнопка не должна открывать окно, где нечего выбирать. */
  test('a clean tree leaves nothing to commit', () => {
    render(<ChatRepoPanel git={git()} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />);

    expect(screen.getByRole('button', { name: /repo.commit/ })).toBeDisabled();
    expect(screen.getByText('files:changes.empty')).toBeInTheDocument();
  });

  /**
   * Главное правило списка: правка на пол-репозитория — обычный ответ ассистента,
   * и панель шириной 320px не место для полусотни строк. Видно первые несколько,
   * остальное считает ссылка в «Файлы».
   */
  test('long change lists are cut, and the rest is one link away', async () => {
    render(<ChatRepoPanel git={git({ changes: dirty(15) })} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />);

    expect(screen.getAllByText(/^file\d+\.js$/)).toHaveLength(4);
    expect(screen.getByText('repo.uncommitted')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /repo.moreChanges/ }));
    expect(navigateToFile).toHaveBeenCalledWith('', 'kb', { changes: true });
  });

  /** Строка файла ведёт туда, где у файла есть diff и откат. */
  test('a file row opens that file in the files panel, in review mode', async () => {
    render(<ChatRepoPanel git={git({ changes: [change('docs/a.md')] })} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />);

    await userEvent.click(screen.getByText('a.md'));
    expect(navigateToFile).toHaveBeenCalledWith('docs/a.md', 'kb', { changes: true });
  });

  /**
   * Ноль не показываем: строка отвечает «разошлись ли», и два нуля рядом с
   * веткой читают на каждом открытии панели, ничего из них не узнавая.
   */
  test('the counters appear only once the branch has actually diverged', () => {
    const { rerender } = render(
      <ChatRepoPanel git={git({ status: { ahead: 0 } })} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />,
    );
    expect(screen.queryByText(/↑|↓/)).not.toBeInTheDocument();

    rerender(
      <ChatRepoPanel git={git({ status: { ahead: 2, behind: 1 } })} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />,
    );
    expect(screen.getByText('↑2')).toBeInTheDocument();
    expect(screen.getByText('↓1')).toBeInTheDocument();
  });

  /** Незавершённый merge — единственное, о чём вкладка говорит без просьбы. */
  test('an unfinished merge is announced, conflicts counted', () => {
    render(
      <ChatRepoPanel
        git={git({ status: { merging: true, conflicts: ['a.js', 'b.js'] } })}
        onOpenCommit={vi.fn()}
        onOpenPush={vi.fn()}
      />,
    );

    expect(screen.getByRole('status')).toHaveTextContent('files:git.mergeConflicts');
  });

  /** Одна строка про последнюю команду, не журнал: вывод целиком лежит в ленте. */
  test('the last command is one line and carries its outcome', () => {
    render(
      <ChatRepoPanel git={git({ last: { command: 'push', ok: false } })} onOpenCommit={vi.fn()} onOpenPush={vi.fn()} />,
    );

    expect(screen.getByText('push')).toBeInTheDocument();
    expect(screen.getByText('repo.outcomeFailed')).toBeInTheDocument();
  });
});
