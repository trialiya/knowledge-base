import { renderHook, act } from '@testing-library/react';
import useCopyFeedback from './useCopyFeedback';
import { COPY_DONE_MS } from '@/constants/ui';

/**
 * Что здесь проверяется: три вещи, которые кадром стенда не поймать, — время
 * («скопировано» гаснет само), одно состояние на список кнопок (загорается
 * ровно одна) и молчаливый отказ writeText.
 */
const writeText = vi.fn();

beforeEach(() => {
  vi.useFakeTimers();
  writeText.mockReset().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});

afterEach(() => vi.useRealTimers());

describe('useCopyFeedback', () => {
  test('гасит «скопировано» само через COPY_DONE_MS', async () => {
    const { result } = renderHook(() => useCopyFeedback());

    await act(() => result.current[1]('9f3c1ab'));
    expect(writeText).toHaveBeenCalledWith('9f3c1ab');
    expect(result.current[0]).toBe(true);

    await act(() => vi.advanceTimersByTimeAsync(COPY_DONE_MS));
    expect(result.current[0]).toBe(null);
  });

  test('держит ключ одной кнопки: клик по другой гасит предыдущую', async () => {
    const { result } = renderHook(() => useCopyFeedback());

    await act(() => result.current[1]('9f3c1ab', 'Commit'));
    expect(result.current[0]).toBe('Commit');

    // Клики разнесены по времени намеренно: срок первого истекает раньше срока
    // второго, и без сброса таймера он погасил бы «скопировано» у второй
    // кнопки — снаружи это выглядело бы как мигание вместо переезда.
    await act(() => vi.advanceTimersByTimeAsync(100));
    await act(() => result.current[1]('1.2 KB', 'Size'));
    expect(result.current[0]).toBe('Size');

    await act(() => vi.advanceTimersByTimeAsync(COPY_DONE_MS - 100));
    expect(result.current[0]).toBe('Size');
  });

  test('на отказе буфера обмена не показывает «скопировано»', async () => {
    writeText.mockRejectedValue(new Error('NotAllowedError'));
    const { result } = renderHook(() => useCopyFeedback());

    await act(() => result.current[1]('9f3c1ab'));
    expect(result.current[0]).toBe(null);
  });
});
