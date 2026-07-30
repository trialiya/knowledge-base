import { renderHook, waitFor } from '@testing-library/react';
import usePreviewCache, { createPreviewStore } from './usePreviewCache';

/**
 * Главное свойство `seed`: он лишь УСКОРЯЕТ первый кадр, но не заменяет загрузку.
 * Раньше синхронная подстановка из дерева обрывала запрос — и предпросмотр
 * документа навсегда оставался обрезанным до 150-символьного снипета из дерева.
 */
describe('usePreviewCache', () => {
  it('показывает затравку сразу и всё равно подменяет её полным значением', async () => {
    const store = createPreviewStore();
    const fetcher = vi.fn().mockResolvedValue({ id: 1, description: 'полный текст' });
    const seed = () => ({ id: 1, description: 'обрез', _stub: true });

    const { result } = renderHook(() => usePreviewCache(store, 1, true, fetcher, { seed }));

    // Первый кадр — затравка, без спиннера.
    expect(result.current.value).toMatchObject({ description: 'обрез' });
    expect(result.current.loading).toBe(false);

    await waitFor(() => expect(result.current.value).toMatchObject({ description: 'полный текст' }));
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current.value._stub).toBeUndefined();
  });

  it('без затравки показывает загрузку, затем значение', async () => {
    const store = createPreviewStore();
    const fetcher = vi.fn().mockResolvedValue({ id: 2 });

    const { result } = renderHook(() => usePreviewCache(store, 2, true, fetcher));

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.value).toMatchObject({ id: 2 }));
    expect(result.current.loading).toBe(false);
  });

  it('готовое значение из кэша важнее затравки и не вызывает повторной загрузки', async () => {
    const store = createPreviewStore();
    const fetcher = vi.fn().mockResolvedValue({ id: 3, description: 'полный текст' });
    const seed = () => ({ id: 3, description: 'обрез', _stub: true });

    const first = renderHook(() => usePreviewCache(store, 3, true, fetcher, { seed }));
    await waitFor(() => expect(first.result.current.value).toMatchObject({ description: 'полный текст' }));

    const second = renderHook(() => usePreviewCache(store, 3, true, fetcher, { seed }));
    expect(second.result.current.value).toMatchObject({ description: 'полный текст' });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('с затравкой ошибка загрузки не стирает уже показанный узел', async () => {
    const store = createPreviewStore();
    const fetcher = vi.fn().mockRejectedValue(new Error('offline'));
    const seed = () => ({ id: 4, description: 'обрез', _stub: true });

    const { result } = renderHook(() => usePreviewCache(store, 4, true, fetcher, { seed }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe(false);
    expect(result.current.value).toMatchObject({ description: 'обрез' });
  });
});
