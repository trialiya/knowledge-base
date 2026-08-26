import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GitBranchList from './GitBranchList';

const many = (n) => Array.from({ length: n }, (_, i) => `feature/branch-number-${i + 1}`);
const filter = () => screen.getByRole('textbox', { name: 'git.filterBranches' });

describe('GitBranchList', () => {
  /** До десятка веток список читается глазами — поле только отняло бы строку и фокус. */
  test('a short list gets no filter field', () => {
    render(<GitBranchList branches={['main', 'feature/x']} current="main" onSelect={vi.fn()} />);

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getAllByRole('menuitem')).toHaveLength(2);
  });

  test('the filter matches a substring anywhere in the name, not just the prefix', async () => {
    render(<GitBranchList branches={[...many(11), 'hotfix/tooltip-crash']} current="main" onSelect={vi.fn()} />);

    await userEvent.type(filter(), 'tooltip');

    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['hotfix/tooltip-crash']);
  });

  test('Enter picks the branch once filtering has left exactly one', async () => {
    const onSelect = vi.fn();
    render(<GitBranchList branches={many(12)} current="main" onSelect={onSelect} />);

    await userEvent.type(filter(), 'number-7{Enter}');

    expect(onSelect).toHaveBeenCalledWith('feature/branch-number-7');
  });

  /** Пока веток больше одной, Enter выбрал бы за пользователя — гадать не за что. */
  test('Enter does nothing while more than one branch matches', async () => {
    const onSelect = vi.fn();
    render(<GitBranchList branches={many(12)} current="main" onSelect={onSelect} />);

    await userEvent.type(filter(), 'number-1{Enter}');

    expect(onSelect).not.toHaveBeenCalled();
  });

  test('a filter that matches nothing says so instead of showing an empty section', async () => {
    render(<GitBranchList branches={many(11)} current="main" onSelect={vi.fn()} />);

    await userEvent.type(filter(), 'нет такой');

    expect(screen.queryAllByRole('menuitem')).toHaveLength(0);
    expect(screen.getByText('git.noBranchMatch')).toBeInTheDocument();
  });
});
