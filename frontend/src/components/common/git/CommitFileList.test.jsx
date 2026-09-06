import { render, screen } from '@testing-library/react';
import CommitFileList from './CommitFileList';

const entry = (path) => ({ path, status: 'M', additions: 1, deletions: 0 });

const selection = {
  checked: new Set(),
  count: 0,
  stateOf: () => 'none',
  toggle: vi.fn(),
};

const renderFlat = (paths) =>
  render(
    <CommitFileList
      entries={paths.map(entry)}
      selection={selection}
      flat
      onLayoutChange={vi.fn()}
      openPath={null}
      onOpen={vi.fn()}
    />,
  );

describe('CommitFileList, плоская раскладка', () => {
  /**
   * Каталог у файла в корне не «пустой» по невнимательности, а отсутствует:
   * подпись, собранная вычитанием длины имени, съедала бы у него последнюю
   * букву — `README.md` показывался бы лежащим в `README.m`.
   */
  test('a file at the repository root is shown without a directory', () => {
    const { container } = renderFlat(['README.md']);

    expect(screen.getByText('README.md')).toBeInTheDocument();
    expect(container.querySelectorAll('.commit-files__dir')).toHaveLength(0);
  });

  test('a nested file is shown with its directory, without the trailing slash', () => {
    const { container } = renderFlat(['src/main/App.java']);

    expect(screen.getByText('App.java')).toBeInTheDocument();
    expect(container.querySelector('.commit-files__dir')).toHaveTextContent('src/main');
  });
});
