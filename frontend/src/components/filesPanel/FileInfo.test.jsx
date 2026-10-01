import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import FileInfo from './FileInfo';
import gitApi from '@/api/gitApi';
import { navigateToCommit } from '@/navigation/fileNavigationBus';
import { commitUrl } from '@/navigation/urlScheme';

// Тело сообщения — единственное поле коммита, за которым «Инфо» ходит отдельной
// просьбой: без `body: true` сервер отдаёт его пустым, и строка исчезла бы молча.

vi.mock('@/api/gitApi');
vi.mock('@/navigation/fileNavigationBus', () => ({ navigateToCommit: vi.fn() }));

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

const CONTENT = { type: 'file', path: 'src/App.jsx', file: { sizeBytes: 10, language: 'jsx', lineCount: 2 } };

const commit = (over = {}) => ({
  hash: 'abcdef1234567890',
  shortHash: 'abcdef12',
  author: 'Кто-то',
  email: 'a@b.c',
  date: '2026-09-01T10:00:00+03:00',
  message: 'Починить перенос строк',
  body: null,
  ...over,
});

const show = () => render(<FileInfo content={CONTENT} loading={false} path="src/App.jsx" project="kb" />);

describe('FileInfo', () => {
  afterEach(() => vi.resetAllMocks());

  test('просит историю вместе с телом сообщения', async () => {
    gitApi.getCommits.mockResolvedValue({ commits: [commit()], truncated: true });

    show();

    await waitFor(() => expect(gitApi.getCommits).toHaveBeenCalled());
    expect(gitApi.getCommits).toHaveBeenCalledWith(
      'src/App.jsx',
      expect.objectContaining({ limit: 1, body: true, project: 'kb' }),
    );
  });

  test('показывает тело коммита, сохраняя его переносы строк', async () => {
    const body = 'Первый абзац.\n\n- пункт\n- ещё пункт';
    gitApi.getCommits.mockResolvedValue({ commits: [commit({ body })], truncated: true });

    show();

    // Без normalizer testing-library схлопнула бы переносы — ровно то, что тест и проверяет.
    const value = await screen.findByText(body, { normalizer: (text) => text });
    expect(value).toHaveClass('info-list__value-text--pre');
  });

  test('в снимке ревизии историю просит от неё, а не от HEAD', async () => {
    gitApi.getCommits.mockResolvedValue({ commits: [commit()], truncated: true });

    render(<FileInfo content={CONTENT} loading={false} path="src/App.jsx" project="kb" rev="v1.2" />);

    await waitFor(() => expect(gitApi.getCommits).toHaveBeenCalled());
    expect(gitApi.getCommits).toHaveBeenCalledWith('src/App.jsx', expect.objectContaining({ rev: 'v1.2' }));
  });

  test('без тела строки для него нет', async () => {
    gitApi.getCommits.mockResolvedValue({ commits: [commit()], truncated: true });

    show();

    await screen.findByText('Починить перенос строк');
    expect(screen.queryByText('info.commitBody')).toBeNull();
  });

  test('хеш последнего коммита ведёт к самому коммиту, а копируется текстом', async () => {
    gitApi.getCommits.mockResolvedValue({ commits: [commit()], truncated: true });
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    show();

    const link = await screen.findByRole('link', { name: 'abcdef12' });
    expect(link).toHaveAttribute('href', commitUrl('abcdef1234567890', 'kb'));
    fireEvent.click(link);
    expect(navigateToCommit).toHaveBeenCalledWith('abcdef1234567890', 'kb');

    fireEvent.click(screen.getByRole('button', { name: /info\.commit$/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('abcdef12'));
  });
});
