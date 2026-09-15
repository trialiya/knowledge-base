import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ToolCallNotifications from './ToolCallNotifications';

// Плашки перестраиваются под ответ, который ещё идёт: следующий вызов того же инструмента
// сливает одиночную плашку в группу, а потом дописывает в неё третью строку. Пережить это
// обязаны и открытые детали, и развёрнутость группы — читают их ровно в тот момент, когда
// модель зовёт дальше.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

// Настоящая модалка ходит за деталями в API; здесь важно лишь то, чей вызов в ней открыт.
vi.mock('./ToolCallDetailModal', () => ({
  default: ({ callId, tc, onClose }) => (
    <div data-testid="detail">
      {`${callId} ${tc.status}`}
      <button data-testid="close" onClick={onClose} />
    </div>
  ),
}));

const edit = (callId, path, status = 'OK') => ({
  name: 'editFile',
  callId,
  status,
  arguments: { path },
});

const plaques = (container) => [...container.querySelectorAll('.tool-call-item')];

const show = (toolCalls) => render(<ToolCallNotifications toolCalls={toolCalls} conversationId="c1" />);

describe('ToolCallNotifications', () => {
  it('показывает группу развёрнутой', () => {
    // Заголовок «×2» говорит лишь, сколько раз инструмент звали; с чем именно — только
    // в строках группы, ради которых плашки и читают.
    const { container } = show([edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java')]);

    expect(plaques(container)).toHaveLength(3);
  });

  it('оставляет детали открытыми, когда одиночная плашка становится группой', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java')]);

    await user.click(plaques(container)[0]);
    expect(screen.getByTestId('detail')).toHaveTextContent('call-1');

    // Тот же инструмент с другими аргументами: плашка уходит под заголовок группы «×2».
    rerender(
      <ToolCallNotifications
        toolCalls={[edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java', 'STARTED')]}
        conversationId="c1"
      />,
    );

    expect(screen.getByTestId('detail')).toHaveTextContent('call-1');
    // И сама плашка остаётся на виду — группа развёрнута.
    expect(plaques(container).map((el) => el.textContent)).toHaveLength(3);
  });

  it('оставляет группу свёрнутой, когда в неё дописывают вызов', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java')]);

    // Свернули руками: следующий вызов того же инструмента не повод разворачивать обратно.
    await user.click(plaques(container)[0]);
    expect(plaques(container)).toHaveLength(1);

    rerender(
      <ToolCallNotifications
        toolCalls={[
          edit('call-1', 'src/App.java'),
          edit('call-2', 'src/Other.java'),
          edit('call-3', 'src/Third.java', 'STARTED'),
        ]}
        conversationId="c1"
      />,
    );

    expect(plaques(container)).toHaveLength(1);
  });

  it('не закрывает детали вызова, уехавшего из этой ленты в соседнюю', async () => {
    // Ряды тоже перестраиваются: склейка соседних рядов из одних вызовов распадается, как
    // только второму прогону становится что показать помимо вызовов, и его вызовы уезжают в
    // свой ряд. Модалка открыта здесь — закрыться из-под читающего она не должна.
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java')]);

    await user.click(plaques(container)[2]);
    expect(screen.getByTestId('detail')).toHaveTextContent('call-2');

    rerender(<ToolCallNotifications toolCalls={[edit('call-1', 'src/App.java')]} conversationId="c1" />);

    expect(plaques(container)).toHaveLength(1);
    expect(screen.getByTestId('detail')).toHaveTextContent('call-2');
  });

  it('показывает в деталях свежее состояние вызова, а не то, что было при открытии', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java', 'STARTED')]);

    await user.click(plaques(container)[0]);
    expect(screen.getByTestId('detail')).toHaveTextContent('call-1 STARTED');

    rerender(<ToolCallNotifications toolCalls={[edit('call-1', 'src/App.java', 'OK')]} conversationId="c1" />);

    expect(screen.getByTestId('detail')).toHaveTextContent('call-1 OK');
  });

  it('открывает детали того вызова, по которому кликнули внутри группы', async () => {
    const user = userEvent.setup();
    const { container } = show([edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java')]);

    // Первая плашка группы — её заголовок, деталей у него нет: кликаем по второй строке.
    await user.click(plaques(container)[2]);

    expect(screen.getByTestId('detail')).toHaveTextContent('call-2');
  });

  it('не выдаёт за успех вызов, исход которого не сохранён', async () => {
    const { container } = show([
      { name: 'editFile', callId: 'call-1', status: 'UNKNOWN', arguments: { path: 'a' } },
      { name: 'editFile', callId: 'call-2', status: 'OK', arguments: { path: 'b' } },
    ]);

    // Заголовок группы берёт худшее из того, что внутри: зелёным он отвечал бы и за вызов,
    // о котором ничего не известно.
    expect(plaques(container)[0].className).toContain('tool-call-item--unknown');
  });
});
