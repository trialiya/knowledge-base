import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { detectArgumentList } from './argumentList';
import ArgumentListView from './ArgumentListView';

// Здесь проверяется не деление аргументов (оно в argumentList.test.js), а то,
// что показ длинного значения переживает сворачивание блока над ним.

// i18n в тестах не инициализируем — берём ключ как подпись.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const LINE_CAP = 300;

const call = () =>
  detectArgumentList(JSON.stringify({ path: 'docs/обзор.md', content: 'строка\n'.repeat(420).trimEnd() }));

const shownLines = () => document.querySelectorAll('.tool-code__line').length;

describe('ArgumentListView', () => {
  it('разворот значения переживает сворачивание блока', async () => {
    render(<ArgumentListView data={call()} />);

    // Единственный блок открыт сразу — раскрываем сам текст.
    expect(shownLines()).toBe(LINE_CAP);
    await userEvent.click(screen.getByRole('button', { name: /showAll|показать|show/i }));
    expect(shownLines()).toBe(420);

    const head = document.querySelector('.tool-args__block-head');
    await userEvent.click(head);
    await userEvent.click(head);
    expect(shownLines()).toBe(420);
  });
});
