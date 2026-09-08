import { act, renderHook } from '@testing-library/react';
import { useRef } from 'react';
import useFindMatches from './useFindMatches';

/** DOM с текстом, разложенным по отдельным узлам — как строки файла в CodeView. */
function tree(...lines) {
  const root = document.createElement('div');
  for (const line of lines) {
    const el = document.createElement('code');
    el.textContent = line;
    root.appendChild(el);
  }
  document.body.appendChild(root);
  return root;
}

afterEach(() => {
  document.body.innerHTML = '';
});

/**
 * Совпадений может стать меньше на том же запросе: переключили markdown-превью
 * или diff, догрузилось содержимое. Показанный индекс при этом прижимается к
 * последнему, и шагать надо от него, а не от сырого счётчика внутри хука.
 */
describe('ходьба по совпадениям после пересбора', () => {
  const mountOn = (root) =>
    renderHook(() => {
      const ref = useRef(root);
      return useFindMatches({ rootRef: ref, query: 'needle', active: true });
    });

  test('стрелка шагает от показанного совпадения, а не от сырого индекса', async () => {
    const root = tree('needle 1', 'needle 2', 'needle 3', 'needle 4', 'needle 5');
    const { result } = mountOn(root);
    expect(result.current.total).toBe(5);

    // Уходим на последнее — сырой индекс 4.
    for (let i = 0; i < 4; i += 1) await act(async () => result.current.goNext());
    expect(result.current.activeIndex).toBe(4);

    // Содержимое сменилось на более короткое: два совпадения вместо пяти.
    // Показанный индекс прижимается к последнему (1), сырой остаётся 4.
    await act(async () => {
      root.textContent = '';
      for (const text of ['needle один', 'needle два']) {
        const el = document.createElement('code');
        el.textContent = text;
        root.appendChild(el);
      }
      await new Promise((r) => setTimeout(r, 200));
    });
    expect(result.current.total).toBe(2);
    expect(result.current.activeIndex).toBe(1);

    // От показанного (1) следующее — 0, по кругу. От сырого (4) вышло бы (4+1)%2
    // = 1, то есть стрелка просто не работала бы.
    await act(async () => result.current.goNext());
    expect(result.current.activeIndex).toBe(0);
  });
});

/**
 * Имена подсветки глобальны для документа, а хуков на экране бывает несколько:
 * бар открытого файла и бар модалки, вставшей поверх него. Каждый объявляет свои
 * Range'и в общий реестр, в имена уходит объединение — иначе экземпляр без
 * совпадений стирал бы чужую подсветку, и вернуть её было бы нечем.
 */
describe('подсветка нескольких экземпляров', () => {
  const highlighted = (name) => window.CSS?.highlights?.get(name)?.size ?? 0;

  // jsdom не реализует CSS Custom Highlight API — подкладываем ровно то, чем
  // хук пользуется: Map имён и конструктор Highlight из набора Range'ей.
  beforeEach(() => {
    // Highlight принимает Range'и отдельными аргументами, а Set — одну итерацию.
    class FakeHighlight extends Set {
      constructor(...ranges) {
        super(ranges);
      }
    }
    window.Highlight = FakeHighlight;
    window.CSS = { ...window.CSS, highlights: new Map() };
  });

  const mount = (root, query, active = true) =>
    renderHook(
      ({ q, on }) => {
        const ref = useRef(root);
        return useFindMatches({ rootRef: ref, query: q, active: on });
      },
      { initialProps: { q: query, on: active } },
    );

  test('экземпляр без совпадений не стирает подсветку соседа', () => {
    const file = mount(tree('needle one', 'needle two'), 'needle');
    expect(file.result.current.total).toBe(2);
    expect(highlighted('kb-find') + highlighted('kb-find-active')).toBe(2);

    // Поверх файла встала модалка с закрытым баром: искать ей нечего.
    const modal = mount(tree('в диалоге пусто'), '', false);
    expect(modal.result.current.total).toBe(0);

    expect(highlighted('kb-find-active')).toBe(1);
    expect(highlighted('kb-find')).toBe(1);
  });

  test('размонтированный экземпляр уносит только свои совпадения', () => {
    const file = mount(tree('needle one', 'needle two'), 'needle');
    const modal = mount(tree('needle в диалоге'), 'needle');
    expect(highlighted('kb-find') + highlighted('kb-find-active')).toBe(3);

    modal.unmount();

    expect(highlighted('kb-find') + highlighted('kb-find-active')).toBe(2);
    file.unmount();
    expect(highlighted('kb-find') + highlighted('kb-find-active')).toBe(0);
  });
});
