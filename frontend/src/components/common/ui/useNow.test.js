import { act, renderHook } from '@testing-library/react';
import useNow from './useNow';

describe('useNow', () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date('2026-09-21T12:00:00Z') }));
  afterEach(() => vi.useRealTimers());

  it('идёт раз в секунду, а с последним подписчиком таймер останавливается', () => {
    const { result, unmount } = renderHook(() => useNow());
    const start = result.current;
    expect(vi.getTimerCount()).toBe(1);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current).toBe(start + 1000);

    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('один таймер на всех подписчиков', () => {
    const first = renderHook(() => useNow());
    const second = renderHook(() => useNow());
    expect(vi.getTimerCount()).toBe(1);
    first.unmount();
    expect(vi.getTimerCount()).toBe(1);
    second.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  // После паузы без подписчиков новый подписчик видит настоящее «сейчас», а не момент остановки.
  it('после паузы без подписчиков отдаёт свежий момент', () => {
    renderHook(() => useNow()).unmount();
    vi.setSystemTime(new Date('2026-09-21T12:10:00Z'));
    const { result } = renderHook(() => useNow());
    expect(result.current).toBe(new Date('2026-09-21T12:10:00Z').getTime());
  });

  it('без live не подписывается и возвращает null', () => {
    const { result } = renderHook(() => useNow(false));
    expect(result.current).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });
});
