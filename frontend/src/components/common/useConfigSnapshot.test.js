import { act, renderHook, waitFor } from '@testing-library/react';
import useConfigSnapshot from './useConfigSnapshot';

/**
 * Кэш снимка конфигурации.
 *
 * Кэш живёт в модуле и общий на весь тест-файл, поэтому у каждого теста своя
 * функция загрузки: она же ключ кэша, и тесты друг другу не мешают.
 */
describe('useConfigSnapshot', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test('отдаёт снимок и не ходит в сеть повторно, пока он свежий', async () => {
    const load = vi.fn().mockResolvedValue({ chat: { defaultModel: { id: 'gpt' } } });

    const first = renderHook(() => useConfigSnapshot(load));
    await waitFor(() => expect(first.result.current.data).not.toBeNull());
    first.unmount();

    // Возврат в группу: данные видны сразу, без второго запроса и без кадра
    // «Загрузка…» — снимок отдан из кэша ещё на первом рендере.
    const second = renderHook(() => useConfigSnapshot(load));
    expect(second.result.current.data).toEqual({ chat: { defaultModel: { id: 'gpt' } } });
    expect(load).toHaveBeenCalledTimes(1);

    // Эффект досетит то же самое значение из кэша уже после проверки — даём ему
    // отработать внутри act, иначе React ругается на обновление вне него.
    await act(async () => {});
  });

  test('две группы, смонтированные подряд, делят один запрос', async () => {
    let resolve;
    const load = vi.fn(() => new Promise((r) => (resolve = r)));

    const a = renderHook(() => useConfigSnapshot(load));
    const b = renderHook(() => useConfigSnapshot(load));
    expect(load).toHaveBeenCalledTimes(1);

    resolve({ ok: true });
    await waitFor(() => expect(a.result.current.data).toEqual({ ok: true }));
    await waitFor(() => expect(b.result.current.data).toEqual({ ok: true }));
  });

  test('после TTL снимок перечитывается — конфиг мог поменяться на сервере', async () => {
    // Двигаем не таймеры, а сами часы: возраст записи в кэше считается по
    // Date.now(), а фейковые таймеры здесь только ломали бы промисы React.
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000);
    const load = vi.fn().mockResolvedValueOnce({ workers: 4 }).mockResolvedValueOnce({ workers: 8 });

    const first = renderHook(() => useConfigSnapshot(load));
    await waitFor(() => expect(first.result.current.data).toEqual({ workers: 4 }));
    first.unmount();

    now.mockReturnValue(1_700_000_031_000);

    const second = renderHook(() => useConfigSnapshot(load));
    await waitFor(() => expect(second.result.current.data).toEqual({ workers: 8 }));
    expect(load).toHaveBeenCalledTimes(2);
  });

  test('отказ не кэшируется: следующее открытие группы пробует снова', async () => {
    const failure = new Error('503');
    const load = vi.fn().mockRejectedValueOnce(failure).mockResolvedValueOnce({ workers: 4 });

    const first = renderHook(() => useConfigSnapshot(load));
    await waitFor(() => expect(first.result.current.error).toBe(failure));
    first.unmount();

    const second = renderHook(() => useConfigSnapshot(load));
    await waitFor(() => expect(second.result.current.data).toEqual({ workers: 4 }));
    expect(load).toHaveBeenCalledTimes(2);
  });
});
