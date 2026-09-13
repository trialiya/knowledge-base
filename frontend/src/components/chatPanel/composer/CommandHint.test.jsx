import { render } from '@testing-library/react';
import CommandHint from './CommandHint';
import { parseChatCommand, COMMAND_BLOCK } from '../run/chatCommands';

// Что именно написано в подсказке — дело словаря (за полнотой следит i18n.test.js);
// здесь проверяется, что строка вообще есть и что имя команды и причина отказа до
// словаря доехали.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const region = (container) => container.querySelector('.composer-command');
const row = (container) => container.querySelector('.composer-command__row');

const compact = parseChatCommand('/сжать про поиск');

describe('CommandHint', () => {
  it('называет команду по её имени', () => {
    const { container } = render(<CommandHint command={compact} />);

    expect(row(container).textContent).toContain('input.command.name.compact');
    expect(row(container).textContent).toContain('input.command.toChat');
  });

  it('обычного вопроса не касается', () => {
    const { container } = render(<CommandHint command={parseChatCommand('что делает /compact?')} />);

    expect(row(container)).toBeNull();
  });

  // Регион объявляется скринридером по смене ТЕКСТА внутри себя, поэтому в разметке
  // он стоит всегда — и пустой тоже. Появившись вместе со своим текстом, он чаще
  // всего не объявляется вовсе, а это единственная замена подсветке там, где её нет.
  it('живой регион остаётся в разметке и без команды', () => {
    const { container } = render(<CommandHint command={null} />);

    expect(region(container)).not.toBeNull();
    expect(region(container).getAttribute('role')).toBe('status');
    expect(region(container).textContent).toBe('');
  });

  it('вместо «уйдёт чату» называет причину, по которой команда не пройдёт', () => {
    const { container } = render(<CommandHint command={compact} block={COMMAND_BLOCK.RUNNING} />);

    expect(row(container).textContent).toContain('input.command.blocked.running');
    expect(row(container).textContent).not.toContain('input.command.toChat');
    expect(row(container).className).toContain('composer-command__row--blocked');
  });
});
