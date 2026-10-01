import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DocLinkTooltip from './DocLinkTooltip';
import gitApi from '@/api/gitApi';
import { navigateToCommit, navigateToFile } from '@/navigation/fileNavigationBus';
import { commitUrl } from '@/navigation/urlScheme';

// Ссылки на коммит и на файл в коммите: настоящий href — каноническая схема (Ctrl+клик
// откроет ровно её), наведение показывает то, о чём говорит ссылка, а не рабочее дерево,
// клик по коммиту ведёт в «Файлы» к этому коммиту.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

vi.mock('@/api/gitApi', () => ({
  default: { getCommit: vi.fn(), getFileContent: vi.fn() },
}));

vi.mock('@/navigation/fileNavigationBus', () => ({
  navigateToCommit: vi.fn(),
  navigateToFile: vi.fn(),
}));

vi.mock('@/components/common/config/useProjectConfig', () => ({
  default: () => ({ defaultProjectId: 'kb' }),
}));

const hash = '0123456789abcdef0123456789abcdef01234567';

const commit = {
  hash,
  shortHash: '0123456',
  author: 'Ann',
  date: '2026-09-01T10:00:00Z',
  message: 'Fix the parser',
  body: 'Why it broke.',
  files: [
    { path: 'a.js', status: 'M', additions: 3, deletions: 1 },
    { path: 'b.js', status: 'A', additions: 2, deletions: 0 },
  ],
};

/** Навести и дождаться карточки: показ отложен таймером. */
const hover = async (user, link) => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  await user.hover(link);
  await act(async () => vi.advanceTimersByTime(250));
  vi.useRealTimers();
};

describe('DocLinkTooltip: ссылка на коммит', () => {
  beforeEach(() => {
    gitApi.getCommit.mockReset().mockResolvedValue(commit);
    gitApi.getFileContent.mockReset();
    navigateToCommit.mockReset();
    navigateToFile.mockReset();
  });

  it('несёт канонический адрес коммита и уводит к нему кликом', async () => {
    const user = userEvent.setup();
    render(<DocLinkTooltip href={`/files?rev=${hash}&project=other`}>0123456</DocLinkTooltip>);

    const link = screen.getByRole('link', { name: '0123456' });
    expect(link.getAttribute('href')).toBe(commitUrl(hash, 'other'));

    await user.click(link);
    expect(navigateToCommit).toHaveBeenCalledWith(hash, 'other');
  });

  it('при наведении показывает коммит из его проекта', async () => {
    const user = userEvent.setup();
    render(<DocLinkTooltip href={`/files?rev=${hash}`}>0123456</DocLinkTooltip>);

    await hover(user, screen.getByRole('link', { name: '0123456' }));

    expect(await screen.findByText('Fix the parser')).toBeInTheDocument();
    // Ссылка без проекта — дефолтный: в запрос идёт разрешённый id.
    expect(gitApi.getCommit).toHaveBeenCalledWith(hash, { project: 'kb' });
    expect(screen.getByText('files:commit.filesSummary')).toBeInTheDocument();
  });

  it('несуществующий коммит говорит об этом, а не крутит загрузку', async () => {
    gitApi.getCommit.mockRejectedValue(new Error('404'));
    const user = userEvent.setup();
    render(<DocLinkTooltip href="/files?rev=deadbee&project=gone">deadbee</DocLinkTooltip>);

    await hover(user, screen.getByRole('link', { name: 'deadbee' }));

    expect(await screen.findByText('docLink.commitNotFound')).toBeInTheDocument();
  });
});

describe('DocLinkTooltip: файл в коммите', () => {
  beforeEach(() => {
    gitApi.getFileContent.mockReset().mockResolvedValue({ path: 'a/B.java', content: 'class B {}', lineCount: 1 });
    navigateToFile.mockReset();
  });

  it('адрес и превью — той ревизии, которую назвала ссылка', async () => {
    const user = userEvent.setup();
    render(<DocLinkTooltip href={`/files?path=a/B.java&rev=${hash}&project=kb#L3`}>B.java</DocLinkTooltip>);

    const link = screen.getByRole('link', { name: 'B.java' });
    expect(link.getAttribute('href')).toBe(`/files/a/B.java?project=kb&rev=${hash}#L3`);

    await hover(user, link);
    await waitFor(() =>
      expect(gitApi.getFileContent).toHaveBeenCalledWith('a/B.java', { from: 1, to: 20, rev: hash, project: 'kb' }),
    );
    // Ревизия рядом с путём — ссылка на сам коммит, в новой вкладке: карточка висит поверх чата.
    const rev = await screen.findByRole('link', { name: '0123456' });
    expect(rev.closest('p')).toHaveTextContent('a/B.java @ 0123456');
    expect(rev.getAttribute('href')).toBe(commitUrl(hash, 'kb'));
    expect(rev).toHaveAttribute('target', '_blank');

    await user.click(screen.getByText('docLink.open'));
    expect(navigateToFile).toHaveBeenCalledWith('a/B.java', 'kb', { rev: hash, changes: false });
  });

  it('«развернуть» в карточке открывает файл целиком — без строк ссылки, в её ревизии', async () => {
    const user = userEvent.setup();
    render(<DocLinkTooltip href={`/files?path=a/B.java&rev=${hash}&project=kb#L3`}>B.java</DocLinkTooltip>);

    await hover(user, screen.getByRole('link', { name: 'B.java' }));
    await user.click(await screen.findByTitle('docLink.expand'));

    await waitFor(() =>
      expect(gitApi.getFileContent).toHaveBeenLastCalledWith('a/B.java', {
        from: undefined,
        to: undefined,
        rev: hash,
        project: 'kb',
        signal: expect.any(AbortSignal),
      }),
    );
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('ссылка без ревизии открывает рабочее дерево, даже если «Файлы» стоят в снимке', async () => {
    const user = userEvent.setup();
    render(<DocLinkTooltip href="/files?path=a/B.java">B.java</DocLinkTooltip>);

    await hover(user, screen.getByRole('link', { name: 'B.java' }));
    await user.click(await screen.findByText('docLink.open'));

    expect(navigateToFile).toHaveBeenCalledWith('a/B.java', null, { rev: '', changes: false });
  });
});
