import { render } from '@testing-library/react';
import CommandHint from './CommandHint';
import { parseChatCommand } from '../run/chatCommands';

// Что именно написано в подсказке — дело словаря (за полнотой следит i18n.test.js);
// здесь проверяется, что строка вообще есть и что имя команды до словаря доехало.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const hint = (container) => container.querySelector('.composer-command');

describe('CommandHint', () => {
  it('называет команду по её имени', () => {
    const { container } = render(<CommandHint command={parseChatCommand('/сжать про поиск')} />);

    expect(hint(container).textContent).toContain('input.command.compact');
  });

  it('обычного вопроса не касается', () => {
    const { container } = render(<CommandHint command={parseChatCommand('что делает /compact?')} />);

    expect(hint(container)).toBeNull();
  });
});
