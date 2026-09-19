import { render } from '@testing-library/react';
import SlashMenuDropdown from './SlashMenuDropdown';
import { slashMenuItems } from './slashMenu';

// Подписи — дело словаря (за полнотой следит i18n.test.js); здесь проверяется, из
// чего список собран и что он говорит про невыполнимую команду. Мок частичный:
// реестр триггеров тянет `@/i18n/index`, а тот поднимает настоящий i18next.
vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key }),
}));

const started = { running: false, chatStarted: true };

const show = (props) =>
  render(
    <SlashMenuDropdown
      items={slashMenuItems('/')}
      query="/"
      selectedIdx={0}
      commandState={started}
      onSelect={() => {}}
      onDismiss={() => {}}
      {...props}
    />,
  ).container;

const rows = (c) => [...c.querySelectorAll('.picker-item')];

describe('SlashMenuDropdown', () => {
  it('разводит команды чату и вставки в сообщение по разделам', () => {
    const c = show();

    const sections = [...c.querySelectorAll('.picker-section')].map((s) => s.textContent);
    expect(sections).toEqual(['input.command.menu.commands', 'input.command.menu.inserts']);
    expect(rows(c)[0].textContent).toContain('/compact');
  });

  it('у команды с хвостом показывает и хвост, и второй синоним', () => {
    const c = show();

    expect(rows(c)[0].querySelector('.picker-item__args').textContent).toContain('input.command.args.compact');
    expect(rows(c)[0].querySelector('.picker-item__alt').textContent).toBe('/сжать');
  });

  // Список открывают чаще всего в новом чате, где /compact и заблокирован: спрятать
  // строку значило бы показать пустой список ровно тому, кому он нужен.
  it('невыполнимую сейчас команду не прячет, а называет причину', () => {
    const c = show({ commandState: { running: false, chatStarted: false } });

    const compact = rows(c)[0];
    expect(compact.className).toContain('picker-item--blocked');
    expect(compact.querySelector('.picker-item__reason').textContent).toBe('input.command.blocked.nothingToCompact');
    expect(compact.querySelector('.picker-item__alt')).toBeNull();
  });

  it('на отфильтрованном списке показывает набранное в шапке', () => {
    const c = show({ items: slashMenuItems('/сж'), query: '/сж' });

    expect(c.querySelector('.picker-dropdown__hint').textContent).toBe('input.command.menu.hintQuery');
    expect(rows(c)).toHaveLength(2);
  });
});
