import { render } from '@testing-library/react';
import Message from './Message';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({
    t: (key, opts) => (opts ? `${key}:${JSON.stringify(opts)}` : key),
    i18n: { language: 'ru' },
  }),
}));

const block = (container) => container.querySelector('.message-block');

/**
 * Контекст после обращения к модели — подсказкой на сегменте ответа. Итог прогона (плашка
 * токенов) остаётся только у последнего сегмента, и там подсказка не нужна: число уже в плашке.
 */
describe('Message — контекст после сообщения', () => {
  test('сегмент без плашки итога показывает контекст подсказкой', () => {
    const { container } = render(<Message sender="ai" text="смотрю" contextTokens={12400} />);

    expect(block(container).getAttribute('title')).toBe('message.contextAfterMessage:{"context":"12.4k"}');
  });

  test('сегмент из одних вызовов инструментов — тоже', () => {
    const { container } = render(
      <Message sender="ai" text="" contextTokens={1040} toolCalls={[{ name: 'listFiles', status: 'OK' }]} />,
    );

    expect(block(container).getAttribute('title')).toContain('message.contextAfterMessage');
  });

  test('у последнего сегмента с плашкой итога подсказки нет', () => {
    const { container } = render(
      <Message sender="ai" text="ответ" contextTokens={1500} usage={{ contextTokens: 1500, outputTokens: 10 }} />,
    );

    expect(block(container).getAttribute('title')).toBeNull();
  });

  test('без замера подсказки нет', () => {
    const { container } = render(<Message sender="ai" text="ответ" />);

    expect(block(container).getAttribute('title')).toBeNull();
  });
});
