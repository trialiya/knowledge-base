import { renderHook, waitFor } from '@testing-library/react';
import useCompareView from './useCompareView';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

const props = (over = {}) => ({
  enabled: true,
  project: 'kb',
  path: 'docs',
  rev: '',
  base: 'main',
  direct: false,
  scope: 'docs',
  branch: 'feature',
  onCompareChange: vi.fn(),
  onPathChange: vi.fn(),
  ...over,
});

describe('useCompareView', () => {
  beforeEach(() => gitApi.compare.mockResolvedValue({ files: [], head: { hash: 'f'.repeat(40) } }));
  afterEach(() => vi.resetAllMocks());

  test('the narrowing reaches both the request and the compare tab', async () => {
    const { result } = renderHook(() => useCompareView(props()));

    await waitFor(() =>
      expect(gitApi.compare).toHaveBeenLastCalledWith('main', expect.objectContaining({ scope: 'docs' })),
    );
    expect(result.current.compareTab.scope).toBe('docs');
  });

  /** Поменять местами — другое направление того же сравнения, а не другое сравнение. */
  test('swapping keeps the narrowing', async () => {
    const onPathChange = vi.fn();
    const { result } = renderHook(() => useCompareView(props({ onPathChange })));
    await waitFor(() => expect(result.current.compareTab.comparison).toBeTruthy());

    result.current.compareTab.onSwap();

    expect(onPathChange).toHaveBeenCalledWith(
      'docs',
      undefined,
      expect.objectContaining({ rev: 'main', base: 'feature', scope: 'docs' }),
    );
  });

  test('changing the mode leaves the narrowing to the address', () => {
    const onCompareChange = vi.fn();
    const { result } = renderHook(() => useCompareView(props({ onCompareChange })));

    result.current.compareTab.onDirectChange(true);

    expect(onCompareChange).toHaveBeenCalledWith('main', true);
  });
});
