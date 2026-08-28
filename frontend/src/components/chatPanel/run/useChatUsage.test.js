import { renderHook } from '@testing-library/react';
import useChatUsage from './useChatUsage';

const answer = (usage) => ({ sender: 'ai', usage });
const measured = { contextTokens: 11000, outputTokens: 320, promptTokens: 31100, modelCalls: 3 };

describe('useChatUsage', () => {
  // Смысл хука — ссылка, а не счёт: на неё завязано мемо вкладок правой панели, которое
  // намеренно не зависит от ленты. Пересоздание объекта на каждый чанк вернуло бы ровно ту
  // пересборку панели вложений, ради избавления от которой мемо и заведено.
  test('на новой ленте с теми же числами отдаёт прежний объект', () => {
    const { result, rerender } = renderHook(({ msgs }) => useChatUsage(msgs, false), {
      initialProps: { msgs: [answer(measured)] },
    });
    const first = result.current;

    // Новый массив и новый пузырь текста — так выглядит следующий чанк стрима.
    rerender({ msgs: [answer(measured), { sender: 'ai', text: 'пишу…' }] });

    expect(result.current).toBe(first);
  });

  test('изменившиеся числа отдаются новым объектом', () => {
    const { result, rerender } = renderHook(({ msgs }) => useChatUsage(msgs, false), {
      initialProps: { msgs: [answer(measured)] },
    });

    rerender({ msgs: [answer(measured), answer({ ...measured, contextTokens: 21000 })] });

    expect(result.current.current).toEqual({ ...measured, contextTokens: 21000 });
    expect(result.current.totals.runs).toBe(2);
  });

  test('неполная история помечается — итоги относятся к загруженной части', () => {
    const { result } = renderHook(() => useChatUsage([answer(measured)], true));

    expect(result.current.partial).toBe(true);
  });
});
