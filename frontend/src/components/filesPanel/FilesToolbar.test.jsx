import { render, screen } from '@testing-library/react';
import FilesToolbar from './FilesToolbar';

vi.mock('@/api/gitApi', () => ({ default: { getRefs: vi.fn(() => new Promise(() => {})) } }));
vi.mock('./FileSearch', () => ({ default: () => <div data-testid="file-search" /> }));

const git = {
  status: { current: 'main', upstream: 'origin/main', ahead: 0, behind: 0, branches: ['main'] },
  capabilities: { commands: true, push: true },
  running: false,
};

const renderToolbar = (props = {}) =>
  render(
    <FilesToolbar
      project="kb"
      changes={false}
      onChangesToggle={vi.fn()}
      flat={false}
      onFlatToggle={vi.fn()}
      onSelect={vi.fn()}
      git={git}
      actions={{}}
      rev=""
      onRevChange={vi.fn()}
      gitRefsToken={0}
      {...props}
    />,
  );

describe('FilesToolbar', () => {
  test('the working tree offers the branch line, the changes mode and file search', () => {
    const { container } = renderToolbar();

    expect(container.querySelector('.git-branch')).not.toBeNull();
    expect(screen.getByText('panel.modeChanges')).toBeInTheDocument();
    expect(screen.getByTestId('file-search')).toBeInTheDocument();
  });

  /**
   * Ради этого режим и отдельный: у коммита не бывает незакоммиченных
   * изменений, а команды двигают рабочее дерево — то есть не то, что показано.
   * Поэтому их здесь нет вовсе, а не «есть, но серые»: серая кнопка обещает,
   * что где-то за ней ответ всё-таки есть.
   */
  test('a snapshot offers nothing that belongs to the working tree', () => {
    const { container } = renderToolbar({ rev: 'v1' });

    expect(container.querySelector('.git-branch')).toBeNull();
    expect(screen.queryByText('panel.modeChanges')).not.toBeInTheDocument();
    expect(screen.queryByTestId('file-search')).not.toBeInTheDocument();
    // А сам ответ на «что я вижу» — остаётся, и называет ревизию.
    expect(screen.getByText('v1')).toBeInTheDocument();
  });
});
