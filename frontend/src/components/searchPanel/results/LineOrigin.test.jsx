import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import gitApi from '@/api/gitApi';
import LineOrigin from './LineOrigin';

vi.mock('@/api/gitApi', () => ({ default: { getLineOrigin: vi.fn() } }));

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, opts) => (opts && 'count' in opts ? `${key}:${opts.count}` : key),
    i18n: { language: 'ru' },
  }),
}));

const HASH2 = 'b'.repeat(40);
const HASH1 = 'a'.repeat(40);

const found = {
  path: 'src/A.java',
  line: 12,
  query: 'total',
  status: 'FOUND',
  steps: [
    { hash: 'c'.repeat(40), author: 'Bob', summary: 'final', path: 'src/A.java', line: 12, text: 'final x = total();' },
    { hash: HASH2, author: 'Alice', summary: 'Use total', path: 'src/Old.java', line: 7, text: 'x = total();' },
  ],
  before: { hash: HASH1, author: 'Alice', summary: 'start', path: 'src/Old.java', line: 7, text: 'x = 1;' },
};

const renderOrigin = (onOpenFile = vi.fn()) =>
  render(<LineOrigin path="src/A.java" line={12} query="total" rev="" project="kb" onOpenFile={onOpenFile} />);

describe('LineOrigin', () => {
  beforeEach(() => vi.mocked(gitApi.getLineOrigin).mockReset());

  it('называет коммит появления и строку до него; путь строки — по кнопке', async () => {
    gitApi.getLineOrigin.mockResolvedValue(found);
    renderOrigin();

    expect(screen.getByText('files.origin.loading')).toBeInTheDocument();
    expect(await screen.findByText('files.origin.found')).toBeInTheDocument();
    expect(gitApi.getLineOrigin).toHaveBeenCalledWith(
      'src/A.java',
      12,
      'total',
      expect.objectContaining({ project: 'kb' }),
    );
    // Ответ — самый старый шаг, «было» — версия до него, путь пока свёрнут.
    expect(screen.getByText('bbbbbbb')).toBeInTheDocument();
    expect(screen.getByText('x = 1;')).toBeInTheDocument();
    expect(screen.queryByText('final x = total();')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'files.origin.path:2' }));
    expect(screen.getByText('final x =', { exact: false })).toBeInTheDocument();
  });

  it('строка версии ведёт к файлу того коммита по его пути и номеру, с вкладкой «Коммит»', async () => {
    gitApi.getLineOrigin.mockResolvedValue(found);
    const onOpenFile = vi.fn();
    renderOrigin(onOpenFile);

    const was = (await screen.findByText('x = 1;')).closest('a');
    expect(was.getAttribute('href')).toContain('/files/src/Old.java');
    expect(was.getAttribute('href')).toContain(`rev=${HASH1}`);
    await userEvent.click(was);
    expect(onOpenFile).toHaveBeenCalledWith(
      'src/Old.java',
      'kb',
      expect.objectContaining({ rev: HASH1, lines: '7', right: 'commit' }),
    );
  });

  it('без ответа — подпись о причине; 503 — про время', async () => {
    gitApi.getLineOrigin.mockResolvedValueOnce({ status: 'UNCOMMITTED', steps: [] });
    const { unmount } = renderOrigin();
    expect(await screen.findByText('files.origin.uncommitted')).toBeInTheDocument();
    unmount();

    gitApi.getLineOrigin.mockRejectedValueOnce(Object.assign(new Error('slow'), { status: 503 }));
    renderOrigin();
    await waitFor(() => expect(screen.getByText('files.origin.timeout')).toBeInTheDocument());
  });
});
