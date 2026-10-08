import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CompareInfo from './CompareInfo';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, params) => (params ? `${key} ${JSON.stringify(params)}` : key),
    i18n: { language: 'ru' },
  }),
}));

const commit = (hash, message) => ({ hash, shortHash: hash.slice(0, 7), message });

const COMPARISON = {
  base: commit('b'.repeat(40), 'main work'),
  head: commit('f'.repeat(40), 'feature work'),
  mergeBase: 'a'.repeat(40),
  diffBase: 'a'.repeat(40),
  log: {
    ahead: 3,
    behind: 1,
    aheadTruncated: false,
    behindTruncated: false,
    commits: [commit('f'.repeat(40), 'feature work'), commit('e'.repeat(40), 'earlier')],
  },
  files: [
    { status: 'M', path: 'a.js', additions: 3, deletions: 1 },
    { status: 'A', path: 'b.js', additions: 2, deletions: 0 },
  ],
};

const show = (props = {}) =>
  render(
    <CompareInfo
      base="main"
      headName="feature"
      project="kb"
      direct={false}
      comparison={COMPARISON}
      loading={false}
      error={null}
      onDirectChange={vi.fn()}
      onSwap={vi.fn()}
      onExit={vi.fn()}
      {...props}
    />,
  );

describe('CompareInfo', () => {
  test('summarises the comparison: both sides, the counters and the files', () => {
    show();

    expect(screen.getByText('main')).toBeInTheDocument();
    expect(screen.getByText('feature')).toBeInTheDocument();
    expect(screen.getByText('compare.aheadBehind {"ahead":"3","behind":"1"}')).toBeInTheDocument();
    expect(screen.getByText('commit.filesSummary {"count":2,"additions":5,"deletions":1}')).toBeInTheDocument();
  });

  /** Коммитов больше, чем перечислено, — это говорится, а не умалчивается. */
  test('lists the commits of the compared side and says how many are left out', () => {
    show();

    expect(screen.getByText('earlier')).toBeInTheDocument();
    expect(screen.getByText('compare.moreCommits {"count":1}')).toBeInTheDocument();
  });

  /** Стороны считаются порознь: точное число одной не становится нижней границей из-за другой. */
  test('only the capped count is marked as a lower bound', () => {
    show({ comparison: { ...COMPARISON, log: { ...COMPARISON.log, behindTruncated: true } } });

    expect(screen.getByText('compare.aheadBehind {"ahead":"3","behind":"1+"}')).toBeInTheDocument();
  });

  test('the diff mode is a pair of toggles, the chosen one pressed', async () => {
    const onDirectChange = vi.fn();
    show({ onDirectChange });

    expect(screen.getByText('compare.fromMergeBase')).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByText('compare.direct'));
    expect(onDirectChange).toHaveBeenCalledWith(true);
  });

  /** База — сам предок: оба режима сравнивают с одним коммитом, а пояснение — про выбранный. */
  test('the explanation follows the chosen mode', () => {
    show({ direct: true });

    expect(screen.getByText('compare.explainDirect {"base":"main"}')).toBeInTheDocument();
  });

  test('an empty list of a revision behind its base points at the direct mode', () => {
    show({ comparison: { ...COMPARISON, files: [], log: { ...COMPARISON.log, ahead: 0, commits: [] } } });

    expect(screen.getByText('compare.onlyBehind {"base":"main"}')).toBeInTheDocument();
  });

  /** Без общего предка сервер сравнил напрямую — выбирать не из чего. */
  test('unrelated histories offer no choice of mode', () => {
    show({ comparison: { ...COMPARISON, mergeBase: null, diffBase: COMPARISON.base.hash } });

    expect(screen.getByText('compare.noMergeBase')).toBeInTheDocument();
    expect(screen.queryByText('compare.direct')).not.toBeInTheDocument();
    expect(screen.getByText('compare.explainDirect {"base":"main"}')).toBeInTheDocument();
  });

  test('swap and exit are offered', async () => {
    const onSwap = vi.fn();
    const onExit = vi.fn();
    show({ onSwap, onExit });

    await userEvent.click(screen.getByText('compare.swap'));
    await userEvent.click(screen.getByText('compare.exit'));
    expect(onSwap).toHaveBeenCalled();
    expect(onExit).toHaveBeenCalled();
  });

  test('a failed comparison says so', () => {
    show({ comparison: null, error: new Error('x') });

    expect(screen.getByText('compare.loadError')).toBeInTheDocument();
  });
});
