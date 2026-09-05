import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CommitDialog from './CommitDialog';

vi.mock('@/components/filesPanel/changes/useChangeDiff', () => ({
  default: vi.fn(() => ({ loading: false, error: null, entry: { path: 'a.js', patch: '@@ -1 +1 @@\n+one\n' } })),
}));

const change = (path, status = 'M') => ({ path, status, additions: 2, deletions: 1 });

const git = ({ changes = [change('src/a.js'), change('docs/b.md')], ...rest } = {}) => ({
  changes,
  project: 'kb',
  refreshToken: 0,
  disabled: false,
  failure: null,
  status: { current: 'main' },
  commit: vi.fn(() => Promise.resolve({ command: 'commit' })),
  ...rest,
});

/**
 * Галочки: первая — «выбрать все», дальше строки в порядке плоской раскладки
 * (по имени файла). По имени их не различить — в тестах t() возвращает ключ без
 * подстановок, и у всех строк подпись одна.
 */
const boxes = () => screen.getAllByRole('checkbox');

describe('CommitDialog', () => {
  /** Главное правило окна: коммитится ровно то, что отмечено. */
  test('commits only the ticked files, with the message', async () => {
    const state = git();
    render(<CommitDialog git={state} onClose={vi.fn()} />);

    // Снимаем docs/b.md — вторую строку списка (a.js, b.md).
    await userEvent.click(boxes()[2]);
    await userEvent.type(screen.getByRole('textbox'), 'ranking weights');
    await userEvent.click(screen.getByRole('button', { name: /git.commitDialog.submitCount/ }));

    expect(state.commit).toHaveBeenCalledWith('ranking weights', ['src/a.js']);
  });

  /** Неотслеживаемый файл в коммит сам не попадёт: галочка снята по умолчанию. */
  test('untracked files start unticked, tracked ones ticked', () => {
    render(
      <CommitDialog git={git({ changes: [change('src/a.js'), change('build/report.html', 'U')] })} onClose={vi.fn()} />,
    );

    expect(boxes()[1]).toHaveAttribute('aria-checked', 'true');
    expect(boxes()[2]).toHaveAttribute('aria-checked', 'false');
  });

  /**
   * Из «Файлов» окно открывают и в режиме дерева, где список никто не спрашивал
   * заранее: «изменений нет» до ответа было бы неправдой ровно в том окне,
   * которое ради них и открыли.
   */
  test('a list still on its way is not an empty repository', () => {
    render(<CommitDialog git={git({ changes: [], changesLoading: true })} onClose={vi.fn()} />);

    expect(screen.getAllByText('common:loading')).toHaveLength(2);
    expect(screen.queryByText('changes.empty')).not.toBeInTheDocument();
  });

  /** Не прочитанный список — тоже не пустой репозиторий. */
  test('a list that failed to load is not an empty repository', () => {
    render(<CommitDialog git={git({ changes: [], changesError: new Error('boom') })} onClose={vi.fn()} />);

    expect(screen.getAllByText('common:loadError')).toHaveLength(2);
    expect(screen.queryByText('changes.empty')).not.toBeInTheDocument();
  });

  /** Пустое сообщение и пустой выбор — два разных способа не получить коммит. */
  test('a commit needs both a message and at least one file', async () => {
    const state = git();
    render(<CommitDialog git={state} onClose={vi.fn()} />);
    const submit = () => screen.getByRole('button', { name: /git.commitDialog.submit/ });

    expect(submit()).toBeDisabled();

    await userEvent.type(screen.getByRole('textbox'), 'message');
    expect(submit()).toBeEnabled();

    await userEvent.click(boxes()[0]);
    expect(submit()).toBeDisabled();
    expect(state.commit).not.toHaveBeenCalled();
  });

  /** Успех закрывает окно, отказ — нет: причину и набранное сообщение читают здесь же. */
  test('the dialog closes on success and stays open on a refusal', async () => {
    const onClose = vi.fn();
    const state = git({ commit: vi.fn(() => Promise.resolve(undefined)) });
    const { rerender } = render(<CommitDialog git={state} onClose={onClose} />);

    await userEvent.type(screen.getByRole('textbox'), 'message');
    await userEvent.click(screen.getByRole('button', { name: /git.commitDialog.submitCount/ }));
    expect(onClose).not.toHaveBeenCalled();

    const ok = git({ commit: vi.fn(() => Promise.resolve({ command: 'commit' })) });
    rerender(<CommitDialog git={ok} onClose={onClose} />);
    await userEvent.type(screen.getByRole('textbox'), 'message');
    await userEvent.click(screen.getByRole('button', { name: /git.commitDialog.submitCount/ }));
    expect(onClose).toHaveBeenCalled();
  });

  /** Пока модель работает, окно открыто, но команда не выстрелит — и говорит почему. */
  test('a busy chat names the reason instead of hiding the button', async () => {
    render(<CommitDialog git={git({ disabled: true, disabledReason: 'busy' })} onClose={vi.fn()} />);

    await userEvent.type(screen.getByRole('textbox'), 'message');
    expect(screen.getByRole('status')).toHaveTextContent('git.blocked.busy');
    expect(screen.getByRole('button', { name: /git.commitDialog.submitCount/ })).toBeDisabled();
  });
});
