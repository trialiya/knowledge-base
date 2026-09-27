import { fireEvent, render, screen } from '@testing-library/react';
import MessageList from './MessageList';

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

const user = (mid, extra = {}) => ({ mid, text: `вопрос ${mid}`, sender: 'user', ...extra });
const ai = (mid, extra = {}) => ({ mid, text: `ответ ${mid}`, sender: 'ai', ...extra });

const answerButtons = () => screen.queryAllByRole('button', { name: /message\.answer$/ });
const retryButtons = () => screen.queryAllByRole('button', { name: /message\.retry$/ });

/**
 * Сообщение из очереди, доставленное за упавшим или остановленным прогоном, остаётся последним
 * вопросом без ответа — follow-up за таким прогоном не стартует. Ответить на него можно только
 * этой кнопкой: у ошибки выше повтора нет (модель успела начать) или он ответил бы не на тот вопрос.
 */
describe('MessageList — вопрос без ответа', () => {
  test('под последним вопросом без ответа — кнопка ответа', () => {
    const onRetry = vi.fn();
    render(
      <MessageList
        conversationId="c1"
        messages={[user('m1'), ai('m2', { error: true }), user('m3')]}
        onRetry={onRetry}
      />,
    );

    expect(answerButtons()).toHaveLength(1);
    fireEvent.click(answerButtons()[0]);
    expect(onRetry).toHaveBeenCalledWith('m3');
  });

  test('ошибка до первого токена за которой встал вопрос: кнопка одна, под вопросом', () => {
    render(
      <MessageList
        conversationId="c1"
        messages={[user('m1'), ai('m2', { error: true, retryMode: 'continue' }), user('m3')]}
        onRetry={vi.fn()}
      />,
    );

    expect(retryButtons()).toHaveLength(0);
    expect(answerButtons()).toHaveLength(1);
  });

  test('ошибка — последний ход: повтор остаётся под ней, кнопки ответа нет', () => {
    render(
      <MessageList
        conversationId="c1"
        messages={[user('m1'), ai('m2', { error: true, retryMode: 'continue' })]}
        onRetry={vi.fn()}
      />,
    );

    expect(retryButtons()).toHaveLength(1);
    expect(answerButtons()).toHaveLength(0);
  });

  test('пока идёт прогон, на вопрос уже отвечают — кнопки нет', () => {
    render(<MessageList conversationId="c1" messages={[user('m1')]} onRetry={vi.fn()} isStreaming />);

    expect(answerButtons()).toHaveLength(0);
  });

  test('«ожидает отправки» — ещё не вопрос истории, отвечать на него нечем', () => {
    render(<MessageList conversationId="c1" messages={[ai('m1'), user('m2', { queued: true })]} onRetry={vi.fn()} />);

    expect(answerButtons()).toHaveLength(0);
  });

  /** Упавшее сжатие после перезагрузки: пузырь ошибки жил только во вкладке, в ленте — сама команда. */
  test('/compact — не вопрос модели, кнопки ответа под ним нет', () => {
    render(
      <MessageList
        conversationId="c1"
        messages={[ai('m1'), user('m2', { text: '/compact про миграции' })]}
        onRetry={vi.fn()}
      />,
    );

    expect(answerButtons()).toHaveLength(0);
  });

  test('после отказа бэка (retryRefused) кнопка ответа не возвращается', () => {
    render(
      <MessageList conversationId="c1" messages={[ai('m1'), user('m2', { retryRefused: true })]} onRetry={vi.fn()} />,
    );

    expect(answerButtons()).toHaveLength(0);
  });

  test('плашка git-команды за вопросом ходом не считается', () => {
    render(
      <MessageList
        conversationId="c1"
        messages={[user('m1'), { mid: 'g1', sender: 'user', gitEvent: { command: 'pull', output: '', ok: true } }]}
        onRetry={vi.fn()}
      />,
    );

    expect(answerButtons()).toHaveLength(1);
  });
});
