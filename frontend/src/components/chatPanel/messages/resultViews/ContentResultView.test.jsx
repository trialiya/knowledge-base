import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { detectContentResult } from './contentResult';
import { parseResult } from './registry';
import ContentResultView from './ContentResultView';

// Здесь проверяется не разбор (он в contentResult.test.js), а то, что показ
// текста переживает переключатели над ним.

const LINE_CAP = 300;

const longFile = (name, lines) =>
  detectContentResult(parseResult(JSON.stringify({ path: name, content: 'строка\n'.repeat(lines).trimEnd() })));

const shownLines = () => document.querySelectorAll('.tool-code__line').length;

describe('ContentResultView', () => {
  it('текст длиннее порога сначала обрезан, «показать целиком» его раскрывает', async () => {
    render(<ContentResultView data={longFile('a.txt', 420)} />);
    expect(shownLines()).toBe(LINE_CAP);

    await userEvent.click(screen.getByRole('button'));
    expect(shownLines()).toBe(420);
  });

  it('разворот переживает переключатель markdown', async () => {
    // Кнопка markdown уносит текст с экрана целиком. Пока «показать целиком»
    // держал сам блок с текстом, разворот уходил вместе с ним, и после возврата
    // к исходнику файл снова был обрезан — хотя раскрыть его уже просили.
    render(<ContentResultView data={longFile('a.md', 420)} />);

    const toggle = document.querySelector('.tool-result__md-toggle');
    await userEvent.click(toggle); // markdown → исходник
    expect(shownLines()).toBe(LINE_CAP);

    await userEvent.click(screen.getByRole('button', { name: /showAll|показать|show/i }));
    expect(shownLines()).toBe(420);

    await userEvent.click(toggle); // исходник → markdown
    await userEvent.click(toggle); // и обратно
    expect(shownLines()).toBe(420);
  });
});
