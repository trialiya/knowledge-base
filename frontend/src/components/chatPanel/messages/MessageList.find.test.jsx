import { fireEvent, render, waitFor } from '@testing-library/react';
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

  // Лента у самого низа: 1000 - 480 - 500 = 20 < 60.
  const metrics = (el) => {
    Object.defineProperty(el, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(el, 'clientHeight', { value: 500, configurable: true });
    el.scrollTop = 480;
    el.scrollTo = vi.fn();
    el.scrollIntoView = vi.fn();
  };

  const streamed = [...messages, msg('m3', 'ещё чанк', 'ai')];

  it('чанк ответа не утаскивает ленту вниз с найденного сообщения', () => {
    const { container, rerender } = render(
      <MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m1" />,
    );
    const list = container.querySelector('.message-list');
    metrics(list);

    // Кадр собственной прокрутки к совпадению.
    fireEvent.scroll(list);
    rerender(<MessageList conversationId="c1" messages={streamed} searchQuery="жираф" activeSearchMid="m1" />);

    expect(list.scrollTop).toBe(480);
  });

  it('прокрутка рукой возвращает автоскролл', () => {
    const { container, rerender } = render(
      <MessageList conversationId="c1" messages={messages} searchQuery="жираф" activeSearchMid="m1" />,
    );
    const list = container.querySelector('.message-list');
    metrics(list);

    fireEvent.wheel(list);
    fireEvent.scroll(list);
    rerender(<MessageList conversationId="c1" messages={streamed} searchQuery="жираф" activeSearchMid="m1" />);

    expect(list.scrollTop).toBe(1000);
  });
});
