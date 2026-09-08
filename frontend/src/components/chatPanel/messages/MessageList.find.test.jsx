import { act, fireEvent, render, waitFor } from '@testing-library/react';
import MessageList from './MessageList';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

const msg = (mid, text, sender = 'user') => ({ mid, dbId: null, text, sender });

/**
 * Подсветка ленты идёт через тот же реестр, что и подсветка файла и модалки
 * (useMatchHighlight), но активным здесь считается всё сообщение целиком: бар
 * ходит по сообщениям, а не по вхождениям.
 */
describe('подсветка совпадений в ленте чата', () => {
  const sizes = () => ({
    all: window.CSS?.highlights?.get('kb-find')?.size ?? 0,
    active: window.CSS?.highlights?.get('kb-find-active')?.size ?? 0,
  });

  beforeEach(() => {
    // jsdom не реализует CSS Custom Highlight API — подкладываем ровно то, чем
    // пользуется реестр: Map имён и конструктор из набора Range'ей.
    class FakeHighlight extends Set {
      constructor(...ranges) {
        super(ranges);
      }
    }
    window.Highlight = FakeHighlight;
    window.CSS = { ...window.CSS, highlights: new Map() };
  });

  const messages = [msg('m1', 'жираф раз и жираф два'), msg('m2', 'жираф три')];

  it('все вхождения активного сообщения контрастнее остальных', async () => {
    render(<MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m1" />);

    await waitFor(() => expect(sizes()).toEqual({ all: 1, active: 2 }));
  });

  it('без активного сообщения контрастного нет', async () => {
    render(<MessageList conversationId="c1" messages={messages} searchQuery="жираф" />);

    await waitFor(() => expect(sizes()).toEqual({ all: 3, active: 0 }));
  });

  /**
   * Совпадение из незагруженной страницы бар догребает пагинацией, и пузырь
   * появляется в ленте позже. Общий список Range'ей к этому моменту ещё старый
   * (он пересобирается по MutationObserver с задержкой), поэтому активный пузырь
   * обходится отдельно — иначе он кадр-другой стоял бы промотанным, но не
   * подсвеченным.
   */
  it('догруженное сообщение подсвечено сразу, не дожидаясь пересбора', async () => {
    const { rerender } = render(
      <MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m0" />,
    );
    await waitFor(() => expect(sizes().all).toBe(3));

    // Догрузили более старую страницу: пузырь m0 появился в ленте.
    rerender(
      <MessageList
        conversationId="c1"
        messages={[msg('m0', 'самый старый жираф'), ...messages]}
        searchQuery="жираф"
        activeSearchMid="m0"
      />,
    );

    // Ни одного тика таймеров: пересбор общего списка ещё не случился.
    expect(sizes().active).toBe(1);
  });

  it('закрытый бар подсветку снимает', async () => {
    const { rerender } = render(
      <MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m1" />,
    );
    await waitFor(() => expect(sizes().all + sizes().active).toBe(3));

    rerender(<MessageList conversationId="c1" messages={messages} searchQuery="" />);

    await waitFor(() => expect(sizes()).toEqual({ all: 0, active: 0 }));
  });
});

/**
 * Прокрутка к найденному сообщению в ЗАНЯТОМ чате. Плавная прокрутка вверх
 * первые кадры ещё «у низа» (порог залипания — 60px), и обработчик скролла
 * возвращал бы автоскролл: следующий чанк ответа уволок бы ленту обратно вниз,
 * то есть переход по ссылке на сообщение тем вернее пропадал бы, чем быстрее
 * печатает модель.
 */
describe('прокрутка к найденному во время прогона', () => {
  const messages = [msg('m1', 'жираф раз'), msg('m2', 'ответ', 'ai')];
  const streamed = [...messages, msg('m3', 'ещё чанк', 'ai')];

  // happy-dom честно доезжает до цели своим таймером и ставит scrollTop сам —
  // здесь проверяется не он, а наша реакция на прокрутку, поэтому обе прокрутки
  // глушим ДО рендера: эффект зовёт их сразу, как только пузырь появился.
  beforeEach(() => {
    vi.spyOn(Element.prototype, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  // Лента у самого низа: 1000 - 480 - 500 = 20 < 60.
  const metrics = (el) => {
    Object.defineProperty(el, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(el, 'clientHeight', { value: 500, configurable: true });
    el.scrollTop = 480;
    el.getBoundingClientRect = () => ({ top: 0, bottom: 500 });
  };

  /** Где стоит искомый пузырь относительно окна ленты. */
  const placeTarget = (container, rect) => {
    container.querySelector('[data-mid="m1"]').getBoundingClientRect = () => rect;
  };

  const mount = () =>
    render(<MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m1" />);

  const stream = (rerender) =>
    rerender(<MessageList conversationId="c1" messages={streamed} searchQuery="жираф" activeSearchMid="m1" />);

  it('чанк ответа не утаскивает ленту вниз с найденного сообщения', () => {
    const { container, rerender } = mount();
    const list = container.querySelector('.message-list');
    metrics(list);

    // Кадр собственной прокрутки к совпадению.
    fireEvent.scroll(list);
    stream(rerender);

    expect(list.scrollTop).toBe(480);
  });

  // Совпадение в последнем сообщении: приехали к самому низу, и догонять ответ
  // лента обязана снова — рост содержимого событий скролла не даёт, вернуть
  // автопрокрутку было бы больше некому.
  it('посадка у самого низа возвращает автоскролл, когда прокрутка успокоилась', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const { container, rerender } = mount();
      const list = container.querySelector('.message-list');
      metrics(list);
      placeTarget(container, { top: 100, bottom: 300 });

      fireEvent.scroll(list);
      await act(async () => vi.advanceTimersByTime(300));
      stream(rerender);

      expect(list.scrollTop).toBe(1000);
    } finally {
      vi.useRealTimers();
    }
  });

  // Тишина в событиях не доказывает, что прокрутка доехала: в занятом чате
  // главный поток встаёт между кадрами дольше таймера. Пока искомого сообщения
  // не видно, это середина прокрутки, а не её конец.
  it('пауза посреди прокрутки не возвращает автоскролл, пока сообщения не видно', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const { container, rerender } = mount();
      const list = container.querySelector('.message-list');
      metrics(list);
      placeTarget(container, { top: -400, bottom: -100 });

      fireEvent.scroll(list);
      await act(async () => vi.advanceTimersByTime(300));
      stream(rerender);

      expect(list.scrollTop).toBe(480);
    } finally {
      vi.useRealTimers();
    }
  });
});
