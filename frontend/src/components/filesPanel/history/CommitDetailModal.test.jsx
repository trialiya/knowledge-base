import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import gitApi from '@/api/gitApi';
import CommitDetailModal from './CommitDetailModal';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, params) => (params ? `${key} ${JSON.stringify(params)}` : key),
    i18n: { language: 'ru' },
  }),
}));

vi.mock('@/api/gitApi', () => ({
  default: { getCommit: vi.fn() },
}));

const HASH = `c2fad2a${'0'.repeat(33)}`;

const file = (status, path, oldPath = null) => ({ status, path, oldPath, additions: 1, deletions: 0 });

const FILES = [
  file('M', 'README.md'),
  file('D', 'svc/csv/CsvService.java'),
  file('R', 'svc/excel/ExcelService.java', 'svc/csv/CsvExportService.java'),
];

const show = (props = {}) =>
  render(<CommitDetailModal hash={HASH} project="kb" onClose={vi.fn()} onOpenSnapshot={vi.fn()} {...props} />);

beforeEach(() => {
  vi.clearAllMocks();
  // Сводка спрашивает коммит со списком файлов, патч — тот же коммит по пути.
  gitApi.getCommit.mockImplementation((rev, { path } = {}) =>
    Promise.resolve({
      hash: HASH,
      parents: [`9e41b0c${'0'.repeat(33)}`],
      author: 'Ivan Petrov',
      email: 'ivan@example.com',
      date: '2026-09-18T15:42:00Z',
      message: 'Выгрузка в Excel',
      body: '',
      files: path ? FILES.filter((f) => f.path === path).map((f) => ({ ...f, patch: '@@ -1 +1 @@\n-a\n+b\n' })) : FILES,
    }),
  );
});

describe('CommitDetailModal', () => {
  test('shows the summary, the files as a tree and the first file in the header', async () => {
    show();

    expect(await screen.findByRole('heading', { name: 'Выгрузка в Excel' })).toBeInTheDocument();
    const tree = screen.getByRole('tree');
    expect(within(tree).getByText('csv')).toBeInTheDocument();
    expect(within(tree).getByText('excel')).toBeInTheDocument();
    // Файл в корне: в шапке одно имя, а не обрезок его же на месте каталога.
    const head = document.querySelector('.commit-detail__file');
    expect(head).toHaveTextContent(/^MREADME\.md$/);
  });

  test('shows a rename with the old path relative to the new one', async () => {
    show();

    expect(await screen.findByText('← csv/CsvExportService.java')).toBeInTheDocument();
  });

  test('switches to a flat list and opens the clicked file', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByRole('tree');

    await user.click(screen.getByTitle('changes.layoutFlat'));
    const list = screen.getByRole('listbox');
    await user.click(within(list).getByText('ExcelService.java'));

    await waitFor(() => expect(document.querySelector('.commit-detail__file-dir')).toHaveTextContent('svc/excel'));
    expect(gitApi.getCommit).toHaveBeenCalledWith(
      HASH,
      expect.objectContaining({ path: 'svc/excel/ExcelService.java' }),
    );
  });
});
