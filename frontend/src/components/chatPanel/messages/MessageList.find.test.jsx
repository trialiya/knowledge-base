import { render, waitFor } from '@testing-library/react';
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
