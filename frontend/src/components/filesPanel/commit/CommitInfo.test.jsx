import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CommitInfo from './CommitInfo';
import { commitUrl } from '@/navigation/urlScheme';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, params) => (params ? `${key} ${JSON.stringify(params)}` : key),
    i18n: { language: 'ru' },
  }),
}));

const COMMIT = {
  hash: 'abcdef1234567890',
  shortHash: 'abcdef1',
  author: 'Кто-то',
  email: 'a@b.c',
  date: '2026-09-01T10:00:00+03:00',
  message: 'Починить перенос строк',
  body: 'Потому что строки рвались',
  files: [
    { status: 'M', path: 'a.js', additions: 3, deletions: 1 },
    { status: 'D', path: 'b.js', additions: 0, deletions: 5 },
  ],
};

const show = (props = {}) =>
  render(
    <CommitInfo
      rev="abcdef1"
      project="kb"
      commit={COMMIT}
      loading={false}
      error={null}
      changesShown={false}
      onShowChanges={vi.fn()}
      {...props}
    />,
  );

describe('CommitInfo', () => {
  test('describes the commit: message, body and a summary of what changed', () => {
    show();

    expect(screen.getByText('Починить перенос строк')).toBeInTheDocument();
    expect(screen.getByText('Потому что строки рвались')).toBeInTheDocument();
    expect(screen.getByText('commit.filesSummary {"count":2,"additions":3,"deletions":6}')).toBeInTheDocument();
    // Ревизия, названная хешем, второй раз не печатается — это тот же коммит.
    expect(screen.queryByText('commit.revision')).not.toBeInTheDocument();
  });

  /** По адресу видно имя ветки, а какой коммит оно сейчас значит — только здесь. */
  test('a revision named otherwise than by hash is shown next to the hash', () => {
    show({ rev: 'main' });

    expect(screen.getByText('commit.revision')).toBeInTheDocument();
    expect(screen.getByText('main')).toBeInTheDocument();
  });

  test('leads to the changed files in the left panel until they are shown', async () => {
    const onShowChanges = vi.fn();
    const { rerender } = show({ onShowChanges });

    await userEvent.click(screen.getByText('commit.showChanges'));
    expect(onShowChanges).toHaveBeenCalledWith(true);

    rerender(
      <CommitInfo
        rev="abcdef1"
        commit={COMMIT}
        loading={false}
        error={null}
        changesShown
        onShowChanges={onShowChanges}
      />,
    );
    expect(screen.queryByText('commit.showChanges')).not.toBeInTheDocument();
  });

  test('a failed request says so instead of an empty list', () => {
    show({ commit: null, error: new Error('boom') });

    expect(screen.getByText('commit.loadError')).toBeInTheDocument();
  });

  /** Ссылка — на коммит по полному хешу, даже если снимок открыт по ветке. */
  test('copies a link to the commit itself', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    show({ rev: 'main' });

    await userEvent.click(screen.getByText('commit.copyLink'));

    expect(writeText).toHaveBeenCalledWith(window.location.origin + commitUrl('abcdef1234567890', 'kb'));
    expect(await screen.findByText('commit.linkCopied')).toBeInTheDocument();
  });
});
