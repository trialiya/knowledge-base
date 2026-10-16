import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ScopeChip from './ScopeChip';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key, params) => (params ? `${key} ${params.name}` : key) }),
}));

describe('ScopeChip', () => {
  test('offers to narrow to the open path', async () => {
    const onScopeChange = vi.fn();
    render(<ScopeChip path="docs/guide" scope="" onScopeChange={onScopeChange} clearLabel="clear" />);

    await userEvent.click(screen.getByRole('button', { name: 'scope.only guide' }));

    expect(onScopeChange).toHaveBeenCalledWith('docs/guide');
  });

  test('the narrowed chip clears with its cross', async () => {
    const onScopeChange = vi.fn();
    render(<ScopeChip path="docs/a.md" scope="docs" onScopeChange={onScopeChange} clearLabel="clear" />);

    await userEvent.click(screen.getByRole('button', { name: 'clear' }));

    expect(onScopeChange).toHaveBeenCalledWith('');
  });

  /** Адрес, набранный руками, может кончаться на «/» — имя от этого не пропадает. */
  test('a trailing slash does not empty the name', () => {
    render(<ScopeChip path="" scope="docs/" onScopeChange={vi.fn()} clearLabel="clear" />);

    expect(screen.getByText('scope.only docs')).toBeInTheDocument();
  });

  test('nothing to narrow to draws nothing', () => {
    const { container } = render(<ScopeChip path="" scope="" onScopeChange={vi.fn()} clearLabel="clear" />);

    expect(container).toBeEmptyDOMElement();
  });
});
