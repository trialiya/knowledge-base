import { render, screen, waitFor } from '@testing-library/react';
import FileInfo from './FileInfo';
import gitApi from '@/api/gitApi';

// Тело сообщения — единственное поле коммита, за которым «Инфо» ходит отдельной
// просьбой: без `body: true` сервер отдаёт его пустым, и строка исчезла бы молча.

vi.mock('@/api/gitApi');

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
    gitApi.getCommits.mockResolvedValue([commit()]);

    show();

    await waitFor(() => expect(gitApi.getCommits).toHaveBeenCalled());
    expect(gitApi.getCommits).toHaveBeenCalledWith(
      'src/App.jsx',
      expect.objectContaining({ limit: 1, body: true, project: 'kb' }),
    );
  });

  test('показывает тело коммита, сохраняя его переносы строк', async () => {
    const body = 'Первый абзац.\n\n- пункт\n- ещё пункт';
    gitApi.getCommits.mockResolvedValue([commit({ body })]);

    show();

    // Без normalizer testing-library схлопнула бы переносы — ровно то, что тест и проверяет.
    const value = await screen.findByText(body, { normalizer: (text) => text });
    expect(value).toHaveClass('info-list__value-text--pre');
  });

  test('без тела строки для него нет', async () => {
    gitApi.getCommits.mockResolvedValue([commit()]);

    show();

    await screen.findByText('Починить перенос строк');
    expect(screen.queryByText('info.commitBody')).toBeNull();
  });
});
