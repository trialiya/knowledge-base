import { render } from '@testing-library/react';
import MarkdownCodeBlock from './MarkdownCodeBlock';

// Компонент разбирает узел `code`, который ему отдаёт react-markdown. Сегодня его
// содержимое — одна строка, но rehype-подсветка разложила бы код на вложенные
// span-ы, и блок не должен от этого опустеть — ни на экране, ни в буфере.

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal()),
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'ru' } }),
}));

test('текст собирается и из вложенных элементов', () => {
  const { container } = render(
    <MarkdownCodeBlock>
      <code className="language-js">
        <span>const</span>
        {' a = 1;'}
      </code>
    </MarkdownCodeBlock>,
  );
  expect(container.querySelector('.code-block__lang').textContent).toBe('js');
  expect(container.querySelector('code').textContent).toBe('const a = 1;');
});

test('узел не элемент — остаётся обычный pre, без шапки', () => {
  const { container } = render(<MarkdownCodeBlock>{'просто текст'}</MarkdownCodeBlock>);
  expect(container.querySelector('.code-block')).toBeNull();
  expect(container.querySelector('pre').textContent).toBe('просто текст');
});
