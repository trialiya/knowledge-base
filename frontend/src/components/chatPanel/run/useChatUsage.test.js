import { renderHook, waitFor } from '@testing-library/react';
import chatApi from '@/api/chatApi';
import useChatUsage from './useChatUsage';

vi.mock('@/api/chatApi', () => ({ default: { getUsage: vi.fn() } }));

const answer = (usage) => ({ sender: 'ai', usage });
const measured = { contextTokens: 11000, outputTokens: 320, promptTokens: 31100, modelCalls: 3 };
const totals = {
  baseContextTokens: 9800,
  runs: 4,
  spent: { promptTokens: 120000 },
  subagentRuns: 0,
  subagentSpent: null,
};

beforeEach(() => {
  chatApi.getUsage.mockReset();
  chatApi.getUsage.mockResolvedValue(totals);
});

describe('useChatUsage', () => {
  // Смысл хука — ссылка, а не счёт: на неё завязано мемо вкладок правой панели, которое
  // намеренно не зависит от ленты. Пересоздание объекта на каждый чанк вернуло бы ровно ту
  // пересборку панели вложений, ради избавления от которой мемо и заведено.
  test('на новой ленте с теми же числами отдаёт прежний объект', async () => {
    const { result, rerender } = renderHook(({ msgs }) => useChatUsage('chat-1', msgs, false), {
      initialProps: { msgs: [answer(measured)] },
    });
    await waitFor(() => expect(result.current.totals).toEqual(totals));
    const first = result.current;

    // Новый массив и новый пузырь текста — так выглядит следующий чанк стрима.
    rerender({ msgs: [answer(measured), { sender: 'ai', text: 'пишу…' }] });

    expect(result.current).toBe(first);
  });

  test('изменившиеся числа отдаются новым объектом', async () => {
    const { result, rerender } = renderHook(({ msgs }) => useChatUsage('chat-1', msgs, false), {
      initialProps: { msgs: [answer(measured)] },
    });
    await waitFor(() => expect(result.current.totals).toEqual(totals));

    rerender({ msgs: [answer(measured), answer({ ...measured, contextTokens: 21000 })] });

    expect(result.current.current).toEqual({ ...measured, contextTokens: 21000 });
  });

  // Итог по чату считает бэкенд: по ленте его не собрать — она страница, и счёт по ней был бы
  // счётом по хвосту разговора. Системная часть оттуда же — её знает только первый прогон чата.
  test('итоги и системная часть приезжают с бэкенда', async () => {
    // Сразу после полного сжатия замера нет: контекст оценивается как системная часть
    // с бэкенда плюс сама сводка — без `baseContextTokens` оценивать было бы не из чего.
    const compacted = { sender: 'ai', compact: { messages: 40 }, usage: { contextTokens: 91000, outputTokens: 700 } };
    const { result } = renderHook(() => useChatUsage('chat-1', [answer(measured), compacted], false));

    await waitFor(() => expect(result.current.totals).toEqual(totals));
    expect(chatApi.getUsage).toHaveBeenCalledWith('chat-1');
    expect(result.current.current).toEqual({ contextTokens: totals.baseContextTokens + 700, estimated: true });
  });

  // Числа меняет прогон, и перечитывать их надо по его завершении: до неё считать нечего.
  test('по завершении прогона итоги перечитываются', async () => {
    const { rerender } = renderHook(({ running }) => useChatUsage('chat-1', [answer(measured)], running), {
      initialProps: { running: true },
    });
    await waitFor(() => expect(chatApi.getUsage).toHaveBeenCalledTimes(1));

    rerender({ running: false });

    await waitFor(() => expect(chatApi.getUsage).toHaveBeenCalledTimes(2));
  });

  // Фоновая суммаризация идёт уже после RUN_DONE, и её раунд оплачен: без этого перечитывания
  // вкладка показывала бы меньше, чем она же после перезагрузки страницы.
  test('плашка сжатия, появившаяся вне прогона, тоже перечитывает итоги', async () => {
    const { rerender } = renderHook(({ msgs }) => useChatUsage('chat-1', msgs, false), {
      initialProps: { msgs: [answer(measured)] },
    });
    await waitFor(() => expect(chatApi.getUsage).toHaveBeenCalledTimes(1));

    rerender({ msgs: [answer(measured), { sender: 'ai', compact: { kind: 'SUMMARIZE' } }] });

    await waitFor(() => expect(chatApi.getUsage).toHaveBeenCalledTimes(2));
  });

  // У черновика своих рядов ещё нет, спрашивать про них нечего.
  test('черновик за итогами не ходит', () => {
    renderHook(() => useChatUsage('new', [], false));

    expect(chatApi.getUsage).not.toHaveBeenCalled();
  });

  // Чужие числа в новом чате хуже пустой вкладки: пока свои не приехали, показывать нечего.
  test('при переходе в другой чат прежние итоги не показываются', async () => {
    const { result, rerender } = renderHook(({ id }) => useChatUsage(id, [answer(measured)], false), {
      initialProps: { id: 'chat-1' },
    });
    await waitFor(() => expect(result.current.totals).toEqual(totals));

    chatApi.getUsage.mockReturnValue(new Promise(() => {}));
    rerender({ id: 'chat-2' });

    expect(result.current.totals).toBeNull();
  });

  // Счёт токенов не тот повод, чтобы ронять вкладку: без него она просто пуста.
  test('ошибка запроса оставляет вкладку без итогов, но не рушит её', async () => {
    chatApi.getUsage.mockRejectedValue(new Error('нет сети'));
    const { result } = renderHook(() => useChatUsage('chat-1', [answer(measured)], false));

    await waitFor(() => expect(chatApi.getUsage).toHaveBeenCalled());
    expect(result.current.totals).toBeNull();
    expect(result.current.current).toEqual(measured);
  });
});
