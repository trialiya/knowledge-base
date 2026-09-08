import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RevisionPicker from './RevisionPicker';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi', () => ({ default: { getRefs: vi.fn() } }));

const renderPicker = (props = {}) =>
  render(<RevisionPicker project="kb" rev="" refsToken={0} onChange={vi.fn()} {...props} />);

beforeEach(() => {
  vi.clearAllMocks();
  gitApi.getRefs.mockResolvedValue({ branches: ['main', 'feature'], tags: ['v2', 'v1'] });
});

describe('RevisionPicker', () => {
  /** Список ревизий — не то, что нужно на каждом открытии панели. */
  test('the revision list is only fetched once the menu is opened', async () => {
    renderPicker();
    expect(gitApi.getRefs).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /revision.workingTree/ }));

    await waitFor(() => expect(gitApi.getRefs).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('menuitem', { name: 'feature' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'v2' })).toBeInTheDocument();
  });

  test('picking a branch reports it and closes the menu', async () => {
    const onChange = vi.fn();
    renderPicker({ onChange });

    await userEvent.click(screen.getByRole('button', { name: /revision.workingTree/ }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'feature' }));

    expect(onChange).toHaveBeenCalledWith('feature');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  /**
   * Хеш в списке не найти — его вводят. Ветку и тег тоже можно набрать: у
   * репозитория с сотней веток это быстрее, чем искать глазами.
   */
  test('a typed revision is accepted on submit', async () => {
    const onChange = vi.fn();
    renderPicker({ onChange });

    await userEvent.click(screen.getByRole('button', { name: /revision.workingTree/ }));
    await userEvent.type(screen.getByLabelText('revision.typeLabel'), 'abc1234{Enter}');

    expect(onChange).toHaveBeenCalledWith('abc1234');
  });

  /** Пустой ввод ревизией не считается: он бы молча выкинул из снимка. */
  test('submitting nothing changes nothing', async () => {
    const onChange = vi.fn();
    renderPicker({ rev: 'v1', onChange });

    await userEvent.click(screen.getByRole('button', { name: /v1/ }));
    await userEvent.type(screen.getByLabelText('revision.typeLabel'), '   {Enter}');

    expect(onChange).not.toHaveBeenCalled();
  });

  /**
   * Выход из снимка — один клик, не через список: из режима выходят чаще, чем
   * переходят в соседнюю ревизию.
   */
  test('the exit button returns to the working tree in one click', async () => {
    const onChange = vi.fn();
    renderPicker({ rev: 'v1', onChange });

    await userEvent.click(screen.getByRole('button', { name: 'revision.exit' }));

    expect(onChange).toHaveBeenCalledWith('');
  });

  /** Снимок обязан быть виден без наведения: перепутать его с диском дорого. */
  test('a snapshot names the revision on the trigger itself', () => {
    const { container } = renderPicker({ rev: 'v1' });

    expect(screen.getByText('v1')).toBeInTheDocument();
    expect(container.querySelector('.rev-picker--snapshot')).not.toBeNull();
  });
});
