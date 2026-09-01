import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ToolCallNotifications from './ToolCallNotifications';

// Плашки перестраиваются под ответ, который ещё идёт: следующий вызов того же инструмента
// сливает одиночную плашку в группу, а потом дописывает в неё третью строку. Открытые
// детали обязаны это пережить — их читают ровно в тот момент, когда модель зовёт дальше.

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
  it('оставляет детали открытыми, когда одиночная плашка становится группой', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java')]);

    await user.click(plaques(container)[0]);
    expect(screen.getByTestId('detail')).toHaveTextContent('call-1');

    // Тот же инструмент с другими аргументами: плашка сворачивается в заголовок группы «×2».
    rerender(
      <ToolCallNotifications
        toolCalls={[edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java', 'STARTED')]}
        conversationId="c1"
      />,
    );

    expect(screen.getByTestId('detail')).toHaveTextContent('call-1');
    // И сама плашка остаётся на виду: группа с открытыми деталями разворачивается сама.
    expect(plaques(container).map((el) => el.textContent)).toHaveLength(3);
  });

  it('не разворачивает группу, детали которой успели закрыть', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java')]);

    await user.click(plaques(container)[0]);
    await user.click(screen.getByTestId('close'));

    rerender(
      <ToolCallNotifications
        toolCalls={[edit('call-1', 'src/App.java'), edit('call-2', 'src/Other.java')]}
        conversationId="c1"
      />,
    );

    expect(plaques(container)).toHaveLength(1);
  });

  it('не разворачивает группу, деталей которой не открывали', async () => {
    const user = userEvent.setup();
    const { container, rerender } = show([edit('call-1', 'src/App.java')]);

    await user.click(plaques(container)[0]);
    rerender(
      <ToolCallNotifications
        toolCalls={[
          edit('call-1', 'src/App.java'),
          { name: 'getFileContent', callId: 'call-2', status: 'OK', arguments: { path: 'a' } },
          { name: 'getFileContent', callId: 'call-3', status: 'OK', arguments: { path: 'b' } },
        ]}
        conversationId="c1"
      />,
    );

    // Одиночная плашка editFile плюс заголовок свёрнутой группы getFileContent ×2.
    expect(plaques(container)).toHaveLength(2);
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

    // Первая плашка группы — её заголовок, он только разворачивает список.
    await user.click(plaques(container)[0]);
    await user.click(plaques(container)[2]);

    expect(screen.getByTestId('detail')).toHaveTextContent('call-2');
  });
});
