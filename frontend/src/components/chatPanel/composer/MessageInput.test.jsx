import { render } from '@testing-library/react';
import MessageInput from './MessageInput';

// Проверяется одно: обещание над полем считано тем же признаком, что спросит отправка
// (useChatRun), — поэтому от самого поля здесь нужен только разбор команды, а не редактор.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}));
vi.mock('./ChipEditor', () => ({
  default: () => <div data-testid="chip-editor" />,
}));
vi.mock('./ComposerToolbar', () => ({ default: () => null }));
vi.mock('./Phrases', () => ({ default: () => <div data-testid="phrases" /> }));

const hint = (container) => container.querySelector('.composer-command')?.textContent ?? '';

const renderInput = (props) =>
  render(<MessageInput chatId="conv-1" staged={[]} initialText="/compact" onSend={() => {}} {...props} />);

describe('MessageInput', () => {
  it('в чате с историей обещает, что команда уйдёт чату', () => {
    const { container } = renderInput({ isEmpty: false });

    expect(hint(container)).toContain('input.command.toChat');
  });

  // Догрузка уже загруженного пустого чата: сообщений в нём нет и после неё, отправка откажет
  // (nothingToCompact) — подсказка обязана сказать то же, а не пообещать сжатие.
  it('не обещает сжатия в пустом чате, пока история догружается', () => {
    const { container } = renderInput({ isEmpty: true, loadingMessages: true });

    expect(hint(container)).toContain('input.command.blocked.nothingToCompact');
    expect(hint(container)).not.toContain('input.command.toChat');
  });

  it('блок git-фраз ждёт загрузки истории, правило команды — нет', () => {
    const loading = renderInput({ isEmpty: true, loadingMessages: true });
    expect(loading.queryByTestId('phrases')).toBeNull();

    const loaded = renderInput({ isEmpty: true, loadingMessages: false });
    expect(loaded.queryByTestId('phrases')).not.toBeNull();
  });
});
